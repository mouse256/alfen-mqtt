package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.time.Instant;
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

    // Per-socket runtime state of the control state machine.
    private boolean charging = false;
    private Instant lastStop = Instant.EPOCH;
    private Instant startConditionSince = null;
    private Instant stopConditionSince = null;
    private int currentPhases = 1;
    private Instant phaseChangeSince = null;

    public enum ChargeMode {
        OFF,
        PV_ONLY,
        PV_AND_MIN,
        FAST
    }

    private static class PowerUsage {
        int produced = 0;
        int consumed = 0;
        Instant lastUpdate = Instant.EPOCH;
    }

    /**
     * Status published for observability on {@code alfen/controller/<charger>/<socket>}.
     */
    public record ControllerStatus(String mode, boolean charging, float targetCurrent, int phases) {
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler, WriterConfig writerConfig) {
        this(vertx, client, chargerName, socket, mqttHandler, writerConfig, Instant::now);
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler, WriterConfig writerConfig, Supplier<Instant> clock) {
        LOG.info("Creating AlfenModbusWriter for {} (socket {})", chargerName, socket);
        this.client = client;
        this.vertx = vertx;
        this.socket = socket;
        this.chargerName = chargerName;
        this.config = writerConfig;
        this.mqttHandler = mqttHandler;
        this.clock = clock;
        // topic: alfen/set/<chargername>/<socket>/<key>
        Pattern pattern = Pattern.compile("alfen/set/" + chargerName + "/(\\d+)/(.*)");

        mqttHandler.register(pattern, TOPIC_SET, this::handleMessage);
        mqttHandler.register(Pattern.compile(Pattern.quote(writerConfig.gridConsumedTopic())), writerConfig.gridConsumedTopic(), this::handlePowerConsumed);
        mqttHandler.register(Pattern.compile(Pattern.quote(writerConfig.gridProducedTopic())), writerConfig.gridProducedTopic(), this::handlePowerProduced);
        if (writerConfig.enabled()) {
            timerId = vertx.setPeriodic(writerConfig.interval().toMillis(), this::update);
        } else {
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
            try {
                chargeMode = ChargeMode.valueOf(payload.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown charge mode for {}: {}", topic, payload);
            }
        } else {
            LOG.warn("Invalid key: {}", topic);
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
        Instant now = clock.get();
        ChargeMode mode = chargeMode;
        LOG.debug("update {} ({}) mode={}", chargerName, socket, mode);

        if (mode == ChargeMode.OFF) {
            stop(now, false);
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
            LOG.warn("Grid reading for {} ({}) is stale (last update {}), disabling", chargerName, socket, lastUpdate);
            stop(now, false);
            return;
        }

        Optional<Integer> chargerDrawOpt = client.getSocketRealPowerSum(socket);
        if (chargerDrawOpt.isEmpty()) {
            LOG.warn("No socket power measurement for socket {}", socket);
            return;
        }
        int chargerDraw = chargerDrawOpt.get();
        // Surplus power available if the charger were off (positive = solar export, negative = grid import).
        int powerAvailable = powerGrid + chargerDraw;
        int gridImport = -powerGrid;
        LOG.debug("Power grid: {}, charger draw: {}, available: {}", powerGrid, chargerDraw, powerAvailable);

        // Spike guard (all modes): too much import => stop now and enter cooldown.
        int spikeLimit = (int) (config.maxGridPower() * (1 + config.spikePercent() / 100.0));
        if (gridImport > spikeLimit) {
            LOG.warn("Grid import {} W exceeds spike limit {} W, stopping {} ({}) immediately", gridImport, spikeLimit, chargerName, socket);
            stop(now, true);
            return;
        }

        if (!charging) {
            handleStart(now, mode, powerAvailable);
        } else {
            handleCharging(now, mode, powerAvailable);
        }
    }

    private void handleStart(Instant now, ChargeMode mode, int powerAvailable) {
        boolean wantStart = switch (mode) {
            case PV_ONLY -> powerAvailable >= config.solarStartPower();
            case PV_AND_MIN, FAST -> true;
            case OFF -> false;
        };

        if (!wantStart) {
            startConditionSince = null;
            disable();
            return;
        }

        if (startConditionSince == null) {
            startConditionSince = now;
        }
        boolean sustained = Duration.between(startConditionSince, now).compareTo(config.startDelay()) >= 0;
        if (sustained && !inCooldown(now)) {
            LOG.info("Start conditions satisfied for {} ({}), starting to charge", chargerName, socket);
            charging = true;
            startConditionSince = null;
            applyCharge(now, mode, powerAvailable);
        } else {
            disable();
        }
    }

    private void handleCharging(Instant now, ChargeMode mode, int powerAvailable) {
        boolean wantStop = switch (mode) {
            case PV_ONLY -> powerAvailable <= -config.solarStopPower();
            case PV_AND_MIN, FAST -> false;
            case OFF -> true;
        };

        if (wantStop) {
            if (stopConditionSince == null) {
                stopConditionSince = now;
            }
            if (Duration.between(stopConditionSince, now).compareTo(config.stopDelay()) >= 0) {
                LOG.info("Stop conditions satisfied for {} ({}), stopping", chargerName, socket);
                stop(now, true);
                return;
            }
        } else {
            stopConditionSince = null;
        }
        applyCharge(now, mode, powerAvailable);
    }

    /**
     * Compute the target current and phase count and write them to the charger.
     */
    private void applyCharge(Instant now, ChargeMode mode, int powerAvailable) {
        int chargeBudget = switch (mode) {
            case PV_ONLY, PV_AND_MIN -> powerAvailable;
            case FAST -> config.maxGridPower() + powerAvailable;
            case OFF -> 0;
        };

        int phases = resolvePhases(now, chargeBudget);
        float amps = clampCurrent(chargeBudget / (VOLTAGE * phases));
        client.setState(socket, amps, phases);
        publishStatus(mode, true, amps, phases);
    }

    /**
     * Decide 1 vs 3 phase charging with its own debounce. 3 phase is only used when the available
     * budget can sustain the 3-phase minimum for {@link WriterConfig#phaseSwitchDelay()}.
     */
    private int resolvePhases(Instant now, int chargeBudget) {
        int threePhaseMinW = (int) (config.minCurrent() * VOLTAGE * 3);
        int desiredPhases = chargeBudget >= threePhaseMinW ? 3 : 1;
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

    private float clampCurrent(double amps) {
        return (float) Math.max(config.minCurrent(), Math.min(config.maxCurrent(), amps));
    }

    private boolean inCooldown(Instant now) {
        return Duration.between(lastStop, now).compareTo(config.cooldown()) < 0;
    }

    /**
     * Disable charging without changing the state machine (used while waiting to start).
     */
    private void disable() {
        client.disable(socket);
        publishStatus(chargeMode, false, 0, currentPhases);
    }

    /**
     * Stop charging and reset the debounce timers. When {@code cooldown} is true a cooldown period
     * is started during which charging may not resume.
     */
    private void stop(Instant now, boolean cooldown) {
        charging = false;
        startConditionSince = null;
        stopConditionSince = null;
        phaseChangeSince = null;
        if (cooldown) {
            lastStop = now;
        }
        client.disable(socket);
        publishStatus(chargeMode, false, 0, currentPhases);
    }

    private void publishStatus(ChargeMode mode, boolean charging, float targetCurrent, int phases) {
        if (mqttHandler == null) {
            return;
        }
        try {
            mqttHandler.publishJson(
                    "alfen/controller/" + chargerName + "/" + socket,
                    new ControllerStatus(mode.name(), charging, targetCurrent, phases)
            );
        } catch (Exception e) {
            LOG.debug("Could not publish controller status", e);
        }
    }
}
