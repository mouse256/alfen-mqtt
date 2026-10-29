package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-socket charging controller (evcc-style load management). Driven periodically, it decides
 * whether to charge and at what current/phases based on the selected {@link ChargeMode}, the
 * configured grid budget and live grid-power readings received over MQTT.
 * <p>
 * All time-based decisions (start/stop debounce, cooldown, phase-switch debounce) use the injected
 * {@link #clock} so they can be unit-tested with simulated time.
 */
public class AlfenModbusWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String KEY_MODE = "mode";
    private static final double VOLTAGE = 230.0;
    private static final String TOPIC_SET = "alfen/set/+/+/+";

    private final AlfenModbusClient client;
    private final String chargerName;
    private final PowerUsage powerUsage = new PowerUsage();
    private final Vertx vertx;
    private final int socket;
    private final WriterConfig config;
    private final MqttHandler mqttHandler;
    private final Supplier<Instant> clock;
    private final long timerId;

    private volatile ChargeMode chargeMode = ChargeMode.OFF;
    // Set once a mode command was received; from then on the retained mode is no longer restored.
    private volatile boolean modeCommandReceived = false;

    // Per-socket runtime state of the control state machine.
    private boolean charging = false;
    private Instant lastStop = Instant.EPOCH;
    private Instant startConditionSince = null;
    private Instant stopConditionSince = null;
    private int currentPhases = 1;
    private Instant phaseChangeSince = null;
    private Instant overLimitSince = null;
    private float targetCurrent = 0;
    private final Supplier<Optional<Double>> socSupplier;
    // Battery level read at the start of each update; empty when unknown.
    private Optional<Double> currentSoc = Optional.empty();
    private SmartPhase lastSmartPhase = null;
    // Last published controller state, used to log state changes only once.
    private ControllerState lastState = null;
    private Instant lastTimerEnd = null;

    public enum ChargeMode {
        OFF,
        PV_ONLY,
        PV_AND_MIN,
        FAST,
        /**
         * Below {@link WriterConfig#smartMinSoc()} like PV_AND_MIN; above it solar driven, with a grid
         * import allowance that shrinks linearly from {@link WriterConfig#smartGridImportLow()} to
         * {@link WriterConfig#smartGridImportHigh()} at 100%. Like PV_ONLY when the battery level is unknown.
         */
        SMART
    }

    /**
     * Sub-phase of the SMART mode, depending on the car's battery level.
     */
    private enum SmartPhase {
        UNKNOWN,
        BELOW_MIN,
        SOLAR
    }

    /**
     * What the controller is doing (or waiting for). Published in the status so it is clear why
     * the charger is or isn't charging.
     */
    public enum ControllerState {
        /** Mode is OFF. */
        OFF,
        /** Grid power readings are too old, charging is disabled for safety. */
        STALE_INPUT,
        /** No power measurement of the socket available yet. */
        NO_MEASUREMENT,
        /** PV_ONLY: not enough solar surplus to start. */
        WAITING_FOR_SURPLUS,
        /** PV_ONLY: start conditions hold, waiting for {@link WriterConfig#startDelay()}. */
        START_DELAY,
        /** Recently stopped, waiting for {@link WriterConfig#cooldown()} before starting again. */
        COOLDOWN,
        /** Charging. */
        CHARGING,
        /** Charging, stop conditions hold, waiting for {@link WriterConfig#stopDelay()}. */
        STOP_DELAY,
        /** Charging, grid import above the limit, waiting for {@link WriterConfig#overLimitDelay()}. */
        OVER_LIMIT,
        /** Charging, waiting for {@link WriterConfig#phaseSwitchDelay()} to switch the phase count. */
        PHASE_SWITCH_DELAY
    }

    private static class PowerUsage {
        int produced = 0;
        int consumed = 0;
        Instant lastUpdate = Instant.EPOCH;
    }

    /**
     * Status published for observability on {@code alfen/controller/<charger>/<socket>}.
     * {@code timerEnd} is the moment the running timer of {@code state} (start delay, cooldown,
     * ...) expires, as ISO-8601 instant, or null when no timer is running. {@code soc} is the
     * (estimated) battery level, null when unknown; {@code allowedGridImport} the grid import in W
     * the SMART mode currently allows, null in other modes or when the battery level is unknown.
     */
    public record ControllerStatus(String mode, String state, boolean charging, float targetCurrent, int phases,
                                   String timerEnd, Double soc, Integer allowedGridImport) {
    }

    /**
     * Topic on which the charge mode can be set.
     */
    public static String modeCommandTopic(String chargerName, int socket) {
        return "alfen/set/" + chargerName + "/" + socket + "/" + KEY_MODE;
    }

    /**
     * Topic on which the current charge mode is published (retained), and restored from at startup.
     */
    public static String modeStateTopic(String chargerName, int socket) {
        return statusTopic(chargerName, socket) + "/" + KEY_MODE;
    }

    /**
     * Topic on which the {@link ControllerStatus} is published.
     */
    public static String statusTopic(String chargerName, int socket) {
        return "alfen/controller/" + chargerName + "/" + socket;
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler, WriterConfig writerConfig,
                             Supplier<Optional<Double>> socSupplier) {
        this(vertx, client, chargerName, socket, mqttHandler, writerConfig, socSupplier, Instant::now);
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler, WriterConfig writerConfig,
                             Supplier<Optional<Double>> socSupplier, Supplier<Instant> clock) {
        LOG.info("Creating AlfenModbusWriter for {} (socket {})", chargerName, socket);
        this.client = client;
        this.vertx = vertx;
        this.socket = socket;
        this.chargerName = chargerName;
        this.config = writerConfig;
        this.mqttHandler = mqttHandler;
        this.clock = clock;
        this.socSupplier = socSupplier;
        // topic: alfen/set/<chargername>/<socket>/<key>
        Pattern pattern = Pattern.compile("alfen/set/" + chargerName + "/(\\d+)/(.*)");

        mqttHandler.register(pattern, TOPIC_SET, this::handleMessage);
        String modeStateTopic = modeStateTopic(chargerName, socket);
        mqttHandler.register(Pattern.compile(Pattern.quote(modeStateTopic)), modeStateTopic, this::handleModeState);
        mqttHandler.register(Pattern.compile(Pattern.quote(writerConfig.gridConsumedTopic())), writerConfig.gridConsumedTopic(), this::handlePowerConsumed);
        mqttHandler.register(Pattern.compile(Pattern.quote(writerConfig.gridProducedTopic())), writerConfig.gridProducedTopic(), this::handlePowerProduced);
        if (writerConfig.enabled()) {
            LOG.info("Controller for {} ({}) enabled{}, charge mode: {}", chargerName, socket,
                    writerConfig.dryRun() ? " (DRY RUN, no modbus writes)" : "", chargeMode);
            timerId = vertx.setPeriodic(writerConfig.interval().toMillis(), this::update);
        } else {
            LOG.info("Controller for {} ({}) disabled", chargerName, socket);
            timerId = 0;
        }
    }

    @Override
    public void close() {
        vertx.cancelTimer(timerId);
    }

    private void handleMessage(String topic, Matcher m, String payload) {
        int msgSocket;
        try {
            msgSocket = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse value as int on topic {}: {}", topic, m.group(1));
            return;
        }
        if (msgSocket != socket) {
            return;
        }
        String key = m.group(2);
        LOG.info("Incoming set message for {} ({}): {} -> {}", chargerName, msgSocket, key, payload);
        if (key.equalsIgnoreCase(KEY_MODE)) {
            ChargeMode newMode;
            try {
                newMode = ChargeMode.valueOf(payload.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown charge mode for {}: {}", topic, payload);
                return;
            }
            modeCommandReceived = true;
            ChargeMode oldMode = chargeMode;
            chargeMode = newMode;
            if (oldMode != newMode) {
                LOG.info("Charge mode for {} ({}) changed: {} -> {}", chargerName, socket, oldMode, newMode);
            } else {
                LOG.info("Charge mode for {} ({}) unchanged: {}", chargerName, socket, newMode);
            }
            publishMode(newMode);
        } else {
            LOG.warn("Invalid key: {}", topic);
        }
    }

    /**
     * Restore the charge mode from the retained mode state at startup. Ignored once a mode command
     * was received, so our own (echoed) retained publishes never override a newer command.
     */
    private void handleModeState(String topic, Matcher m, String payload) {
        if (modeCommandReceived) {
            return;
        }
        ChargeMode restored;
        try {
            restored = ChargeMode.valueOf(payload.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown retained charge mode on {}: {}", topic, payload);
            return;
        }
        ChargeMode oldMode = chargeMode;
        chargeMode = restored;
        if (oldMode != restored) {
            LOG.info("Charge mode for {} ({}) restored: {} -> {}", chargerName, socket, oldMode, restored);
        }
    }

    private void handlePowerConsumed(String topic, Matcher matchedTopic, String payload) {
        try {
            int power = (int) (Double.parseDouble(payload) * 1000);
            LOG.debug("Received power consumed message: {} -- {}", payload, power);
            synchronized (powerUsage) {
                powerUsage.consumed = power;
                powerUsage.lastUpdate = clock.get();
            }
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse power consumed payload on {}: {}", topic, payload);
        }
    }

    private void handlePowerProduced(String topic, Matcher matchedTopic, String payload) {
        try {
            int power = (int) (Double.parseDouble(payload) * 1000);
            LOG.debug("Received power produced message: {} -- {}", payload, power);
            synchronized (powerUsage) {
                powerUsage.produced = power;
                powerUsage.lastUpdate = clock.get();
            }
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse power produced payload on {}: {}", topic, payload);
        }
    }

    void update(Long aLong) {
        try {
            runUpdate();
        } catch (Exception e) {
            // Never let an exception escape a periodic timer handler: vert.x cancels the timer
            // when the handler throws, and a dead controller means no more keep-alive writes,
            // so the charger would fall back to its safe current (validity time, default 60s).
            LOG.error("Controller update for {} ({}) failed", chargerName, socket, e);
        }
    }

    private void runUpdate() {
        Instant now = clock.get();
        ChargeMode mode = chargeMode;
        LOG.debug("update {} ({}) mode={}", chargerName, socket, mode);
        currentSoc = socSupplier.get();
        logSmartPhase(mode);

        if (mode == ChargeMode.OFF) {
            stop(now, false, ControllerState.OFF);
            return;
        }

        int powerGrid;
        Instant lastUpdate;
        synchronized (powerUsage) {
            powerGrid = powerUsage.produced - powerUsage.consumed;
            lastUpdate = powerUsage.lastUpdate;
        }

        // Safety: don't act on stale grid readings.
        if (Duration.between(lastUpdate, now).compareTo(config.inputTimeout()) > 0) {
            LOG.debug("Grid reading for {} ({}) is stale (last update {})", chargerName, socket, lastUpdate);
            stop(now, false, ControllerState.STALE_INPUT);
            return;
        }

        Optional<Integer> chargerDrawOpt = client.getSocketRealPowerSum(socket);
        if (chargerDrawOpt.isEmpty()) {
            LOG.debug("No socket power measurement for socket {}", socket);
            // Keep-alive: the spec requires actively sending commands to the charger, otherwise it
            // enters safe mode. Re-apply the current state so a write happens even without a measurement.
            if (charging) {
                client.setState(socket, targetCurrent, currentPhases);
            } else {
                client.disable(socket);
            }
            publishStatus(mode, ControllerState.NO_MEASUREMENT, null, charging, charging ? targetCurrent : 0, currentPhases);
            return;
        }
        int chargerDraw = chargerDrawOpt.get();
        // Surplus power available if the charger were off (positive = solar export, negative = grid import).
        int powerAvailable = powerGrid + chargerDraw;
        int gridImport = -powerGrid;
        LOG.debug("Power grid: {}, charger draw: {}, available: {}", powerGrid, chargerDraw, powerAvailable);

        // Hard requirement (spec): never consume too much from the grid. Don't start charging when
        // the grid import is already at/over the budget — this prevents a start→over-limit→stop→
        // cooldown→start cycle in modes that always want to start (PV_AND_MIN, FAST, SMART below
        // min-soc). For solar-driven modes the start thresholds already prevent this.
        if (!charging && gridImport >= config.maxGridPower()) {
            LOG.debug("Not starting {} ({}) in mode {}: grid import {}W is at/over budget {}W",
                    chargerName, socket, mode, gridImport, config.maxGridPower());
            disable(mode, ControllerState.WAITING_FOR_SURPLUS, null);
            return;
        }

        if (charging && handleOverLimit(now, mode, powerAvailable, gridImport)) {
            return;
        }

        if (!charging) {
            handleStart(now, mode, powerAvailable);
        } else {
            handleCharging(now, mode, powerAvailable, powerGrid);
        }
    }

    private void handleStart(Instant now, ChargeMode mode, int powerAvailable) {
        boolean wantStart = switch (mode) {
            case PV_ONLY -> pvOnlyStart(powerAvailable);
            case PV_AND_MIN, FAST -> true;
            case SMART -> switch (smartPhase()) {
                case UNKNOWN -> pvOnlyStart(powerAvailable);
                case BELOW_MIN -> true;
                case SOLAR -> powerAvailable - minChargePower() >= -allowedImport(currentSoc.get());
            };
            case OFF -> false;
        };

        if (!wantStart) {
            startConditionSince = null;
            disable(mode, ControllerState.WAITING_FOR_SURPLUS, null);
            return;
        }

        if (startConditionSince == null) {
            startConditionSince = now;
        }
        // The start delay only rides out short solar peaks in the solar driven modes; in the other
        // modes the start condition always holds, so they start right away (the cooldown still applies).
        Instant startAt = isSolarDriven(mode) ? startConditionSince.plus(config.startDelay()) : now;
        Instant cooldownEnd = lastStop.plus(config.cooldown());
        if (!now.isBefore(startAt) && !inCooldown(now)) {
            LOG.info("Start conditions satisfied for {} ({}), starting to charge", chargerName, socket);
            charging = true;
            startConditionSince = null;
            applyCharge(now, mode, powerAvailable);
        } else if (cooldownEnd.isAfter(startAt)) {
            disable(mode, ControllerState.COOLDOWN, cooldownEnd);
        } else {
            disable(mode, ControllerState.START_DELAY, startAt);
        }
    }

    private void handleCharging(Instant now, ChargeMode mode, int powerAvailable, int powerGrid) {
        boolean wantStop = switch (mode) {
            case PV_ONLY -> pvOnlyStop(powerGrid);
            case PV_AND_MIN, FAST -> false;
            case SMART -> switch (smartPhase()) {
                case UNKNOWN -> pvOnlyStop(powerGrid);
                case BELOW_MIN -> false;
                case SOLAR -> powerAvailable - minChargePower()
                        < -allowedImport(currentSoc.get()) - config.smartStopHysteresis();
            };
            case OFF -> true;
        };

        if (wantStop) {
            if (stopConditionSince == null) {
                stopConditionSince = now;
            }
            if (Duration.between(stopConditionSince, now).compareTo(config.stopDelay()) >= 0) {
                LOG.info("Stop conditions satisfied for {} ({}), stopping", chargerName, socket);
                stop(now, true, ControllerState.COOLDOWN);
                return;
            }
        } else {
            stopConditionSince = null;
        }
        applyCharge(now, mode, powerAvailable);
    }

    /**
     * Over-limit guard (all modes, while charging): when grid import stays above the budget for
     * {@link WriterConfig#overLimitDelay()}, step down. On 3 phase switch to 1 phase right away
     * (bypassing the phase-switch debounce); on 1 phase at minimum current stop with cooldown.
     * Above the minimum current on 1 phase the normal loop already lowers the current.
     *
     * @return true when charging was stopped and this update is done
     */
    private boolean handleOverLimit(Instant now, ChargeMode mode, int powerAvailable, int gridImport) {
        if (gridImport <= config.maxGridPower()) {
            overLimitSince = null;
            return false;
        }
        if (overLimitSince == null) {
            overLimitSince = now;
        }
        if (Duration.between(overLimitSince, now).compareTo(config.overLimitDelay()) < 0) {
            return false;
        }
        overLimitSince = null;
        if (currentPhases == 3) {
            LOG.warn("Grid import {} W over limit {} W for {}, switching {} ({}) to 1 phase", gridImport, config.maxGridPower(), config.overLimitDelay(), chargerName, socket);
            currentPhases = 1;
            phaseChangeSince = null;
            return false;
        }
        if (chargeBudget(mode, powerAvailable) / VOLTAGE <= config.minCurrent()) {
            LOG.warn("Grid import {} W over limit {} W for {} at minimum current, stopping {} ({})", gridImport, config.maxGridPower(), config.overLimitDelay(), chargerName, socket);
            stop(now, true, ControllerState.COOLDOWN);
            return true;
        }
        return false;
    }

    private int chargeBudget(ChargeMode mode, int powerAvailable) {
        return switch (mode) {
            case PV_ONLY, PV_AND_MIN -> powerAvailable;
            case FAST -> config.maxGridPower() + powerAvailable;
            // SMART: all sub-phases charge on the solar surplus only (spec: below min-soc "same as
            // PV_AND_MIN", above it "PV_ONLY alike"). The allowed grid import only relaxes the
            // start/stop thresholds, it is not added to the budget — otherwise the controller
            // would deliberately target importing power from the grid while charging.
            case SMART -> powerAvailable;
            case OFF -> 0;
        };
    }

    /**
     * PV_ONLY start: the spec says to start when there is more than {@code solarStartPower} exported
     * to the grid, even if that means importing the rest from the grid (the car cannot charge below
     * minimum current). So the threshold is on the raw surplus, not on the surplus minus minimum charge.
     */
    private boolean pvOnlyStart(int powerAvailable) {
        return powerAvailable >= config.solarStartPower();
    }

    /**
     * PV_ONLY stop: the spec says to stop when the import from the grid is above
     * {@code solarStopPower}. This is the actual grid import (including the car), not normalized
     * to minimum current.
     */
    private boolean pvOnlyStop(int powerGrid) {
        return powerGrid < -config.solarStopPower();
    }

    /**
     * Whether the start condition depends on solar, so it gets the start delay.
     */
    private boolean isSolarDriven(ChargeMode mode) {
        return mode == ChargeMode.PV_ONLY || (mode == ChargeMode.SMART && smartPhase() != SmartPhase.BELOW_MIN);
    }

    private SmartPhase smartPhase() {
        if (currentSoc.isEmpty()) {
            return SmartPhase.UNKNOWN;
        }
        return currentSoc.get() < config.smartMinSoc() ? SmartPhase.BELOW_MIN : SmartPhase.SOLAR;
    }

    /**
     * Grid import in W the SMART mode allows at the given battery level: {@link WriterConfig#smartGridImportLow()}
     * up to {@link WriterConfig#smartMinSoc()}, {@link WriterConfig#smartGridImportHigh()} at 100%, linear in between.
     */
    int allowedImport(double soc) {
        double minSoc = config.smartMinSoc();
        if (soc <= minSoc) {
            return config.smartGridImportLow();
        }
        if (soc >= 100) {
            return config.smartGridImportHigh();
        }
        double fraction = (soc - minSoc) / (100 - minSoc);
        return (int) Math.round(config.smartGridImportLow()
                + (config.smartGridImportHigh() - config.smartGridImportLow()) * fraction);
    }

    /**
     * Log once when the SMART mode switches between its sub-phases.
     */
    private void logSmartPhase(ChargeMode mode) {
        if (mode != ChargeMode.SMART) {
            lastSmartPhase = null;
            return;
        }
        SmartPhase phase = smartPhase();
        if (phase == lastSmartPhase) {
            return;
        }
        lastSmartPhase = phase;
        switch (phase) {
            case UNKNOWN -> LOG.info("SMART {} ({}): battery level unknown, behaving like PV_ONLY", chargerName, socket);
            case BELOW_MIN -> LOG.info("SMART {} ({}): battery {}% below minimum {}%, charging like PV_AND_MIN (allowed grid import {} W)",
                    chargerName, socket, String.format("%.1f", currentSoc.get()), config.smartMinSoc(), allowedImport(currentSoc.get()));
            case SOLAR -> LOG.info("SMART {} ({}): battery {}% above minimum {}%, solar phase (allowed grid import now {} W)",
                    chargerName, socket, String.format("%.1f", currentSoc.get()), config.smartMinSoc(), allowedImport(currentSoc.get()));
        }
    }

    /**
     * Compute the target current and phase count and write them to the charger.
     */
    private void applyCharge(Instant now, ChargeMode mode, int powerAvailable) {
        int chargeBudget = chargeBudget(mode, powerAvailable);

        int phases = resolvePhases(now, chargeBudget);
        float amps = clampCurrent(chargeBudget / (VOLTAGE * phases));
        targetCurrent = amps;
        client.setState(socket, amps, phases);

        // Report the pending action with the timer that expires first in the order it is acted upon.
        if (stopConditionSince != null) {
            publishStatus(mode, ControllerState.STOP_DELAY, stopConditionSince.plus(config.stopDelay()), true, amps, phases);
        } else if (overLimitSince != null) {
            publishStatus(mode, ControllerState.OVER_LIMIT, overLimitSince.plus(config.overLimitDelay()), true, amps, phases);
        } else if (phaseChangeSince != null) {
            publishStatus(mode, ControllerState.PHASE_SWITCH_DELAY, phaseChangeSince.plus(config.phaseSwitchDelay()), true, amps, phases);
        } else {
            publishStatus(mode, ControllerState.CHARGING, null, true, amps, phases);
        }
    }

    /**
     * Decide 1 vs 3 phase charging with its own debounce ({@link WriterConfig#phaseSwitchDelay()}).
     * Switching up requires the budget to cover the 3-phase minimum plus
     * {@link WriterConfig#threePhaseMargin()}, so a small load increase doesn't immediately push
     * the import over the limit; switching down happens once the 3-phase minimum isn't covered.
     */
    private int resolvePhases(Instant now, int chargeBudget) {
        int threePhaseMinW = (int) (config.minCurrent() * VOLTAGE * 3);
        int threshold = currentPhases == 3 ? threePhaseMinW : threePhaseMinW + config.threePhaseMargin();
        int desiredPhases = chargeBudget >= threshold ? 3 : 1;
        if (desiredPhases == currentPhases) {
            phaseChangeSince = null;
            return currentPhases;
        }
        if (phaseChangeSince == null) {
            phaseChangeSince = now;
        }
        if (Duration.between(phaseChangeSince, now).compareTo(config.phaseSwitchDelay()) >= 0) {
            LOG.info("Switching {} ({}) from {} to {} phase", chargerName, socket, currentPhases, desiredPhases);
            currentPhases = desiredPhases;
            phaseChangeSince = null;
        }
        return currentPhases;
    }

    /**
     * Power drawn by the charger at minimum current on a single phase. The PV_ONLY start/stop
     * thresholds are evaluated against the grid power as it would be with the car charging at this
     * minimum. Single phase is used because the phase-switch logic falls back to 1 phase when the
     * 3-phase minimum can't be sustained.
     */
    private int minChargePower() {
        return (int) (config.minCurrent() * VOLTAGE);
    }

    private float clampCurrent(double amps) {
        return (float) Math.max(config.minCurrent(), Math.min(config.maxCurrent(), amps));
    }

    private boolean inCooldown(Instant now) {
        return Duration.between(lastStop, now).compareTo(config.cooldown()) < 0;
    }

    /**
     * Disable charging without changing the state machine (used while waiting to start).
     */
    private void disable(ChargeMode mode, ControllerState state, Instant timerEnd) {
        client.disable(socket);
        publishStatus(mode, state, timerEnd, false, 0, currentPhases);
    }

    /**
     * Stop charging and reset the debounce timers. When {@code cooldown} is true a cooldown period
     * is started during which charging may not resume.
     * Needed to call this the whole time, as it needs to continue writing to the alfen register.
     */
    private void stop(Instant now, boolean cooldown, ControllerState state) {
        charging = false;
        startConditionSince = null;
        stopConditionSince = null;
        phaseChangeSince = null;
        overLimitSince = null;
        targetCurrent = 0;
        Instant timerEnd = null;
        if (cooldown) {
            lastStop = now;
            timerEnd = now.plus(config.cooldown());
        }
        client.disable(socket);
        publishStatus(chargeMode, state, timerEnd, false, 0, currentPhases);
    }

    /**
     * Log the controller state once when it (or its timer) changes, so the log shows why the
     * charger is or isn't charging without repeating every interval.
     */
    private void logStateChange(ChargeMode mode, ControllerState state, Instant timerEnd) {
        if (state == lastState && Objects.equals(timerEnd, lastTimerEnd)) {
            return;
        }
        boolean stateChanged = state != lastState;
        lastState = state;
        lastTimerEnd = timerEnd;
        if (!stateChanged) {
            // Same state with a restarted timer (e.g. start delay restarted): only worth a debug line.
            LOG.debug("Controller {} ({}) {} timer now ends at {}", chargerName, socket, state, timerEnd);
            return;
        }
        String timer = timerEnd == null ? ""
                : " until " + timerEnd + " (" + Duration.between(clock.get(), timerEnd).toSeconds() + "s)";
        if (state == ControllerState.STALE_INPUT || state == ControllerState.NO_MEASUREMENT) {
            LOG.warn("Controller {} ({}) mode {}: state {}{}", chargerName, socket, mode, state, timer);
        } else {
            LOG.info("Controller {} ({}) mode {}: state {}{}", chargerName, socket, mode, state, timer);
        }
    }

    private void publishMode(ChargeMode mode) {
        try {
            mqttHandler.publish(modeStateTopic(chargerName, socket), mode.name(), true);
        } catch (Exception e) {
            LOG.warn("Could not publish charge mode", e);
        }
    }

    private void publishStatus(ChargeMode mode, ControllerState state, Instant timerEnd, boolean charging, float targetCurrent, int phases) {
        logStateChange(mode, state, timerEnd);
        if (mqttHandler == null) {
            return;
        }
        try {
            mqttHandler.publishJson(
                    statusTopic(chargerName, socket),
                    new ControllerStatus(mode.name(), state.name(), charging, targetCurrent, phases,
                            timerEnd == null ? null : timerEnd.toString(),
                            currentSoc.map(soc -> Math.round(soc * 10) / 10.0).orElse(null),
                            mode == ChargeMode.SMART ? currentSoc.map(this::allowedImport).orElse(null) : null)
            );
        } catch (Exception e) {
            LOG.debug("Could not publish controller status", e);
        }
    }
}
