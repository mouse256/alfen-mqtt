package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "writer")
public interface WriterConfig {

    /**
     * Master gate for the charging controller. When false no writer timers are started.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Dry run: the controller runs, logs its decisions and publishes its status, but nothing is
     * written to the charger over modbus.
     */
    @WithDefault("false")
    boolean dryRun();

    /**
     * How often the control loop runs.
     */
    @WithDefault("PT5s")
    Duration interval();

    /**
     * Minimum charge current in A per phase (the charger cannot go below this).
     */
    @WithDefault("6")
    int minCurrent();

    /**
     * Maximum charge current in A per phase (charger cap; target is clamped to this).
     */
    @WithDefault("16")
    int maxCurrent();

    /**
     * Total grid-import budget in W. The FAST mode never tries to import more than this, and while
     * charging, import above this for {@link #overLimitDelay()} makes the controller step down.
     */
    @WithDefault("7400")
    int maxGridPower();

    /**
     * How long grid import may stay above {@link #maxGridPower()} while charging before the
     * controller steps down: 3 phase -> 1 phase immediately, or stop (with cooldown) when already
     * charging on 1 phase at minimum current.
     */
    @WithDefault("PT30S")
    Duration overLimitDelay();

    /**
     * Start conditions must hold this long before charging actually starts.
     */
    @WithDefault("PT2M")
    Duration startDelay();

    /**
     * Stop conditions must hold this long before charging stops.
     */
    @WithDefault("PT3M")
    Duration stopDelay();

    /**
     * After a stop, charging may not resume until this much time has passed.
     */
    @WithDefault("PT5M")
    Duration cooldown();

    /**
     * Debounce for switching between 1 and 3 phase charging.
     */
    @WithDefault("PT5M")
    Duration phaseSwitchDelay();

    /**
     * Headroom in W on top of the 3-phase minimum charge power before switching from 1 to 3 phase.
     * Switching back to 1 phase only happens below the 3-phase minimum itself, so in between the
     * current phase count is kept (hysteresis).
     */
    @WithDefault("300")
    int threePhaseMargin();

    /**
     * In PV_ONLY mode: charging may start when more than this many W are exported to the grid
     * (raw grid export, per spec: "even if that means importing the rest from the grid").
     */
    @WithDefault("500")
    int solarStartPower();

    /**
     * In PV_ONLY mode: charging stops when the actual import from the grid exceeds this many W.
     */
    @WithDefault("1000")
    int solarStopPower();

    /**
     * Grid readings older than this are considered stale and disable charging for safety.
     */
    @WithDefault("PT1M")
    Duration inputTimeout();

    /**
     * MQTT topic carrying instantaneous power consumed from the grid (payload in kW).
     */
    @WithDefault("slimmelezer/sensor/power_consumed/state")
    String gridConsumedTopic();

    /**
     * MQTT topic carrying instantaneous power returned to the grid (payload in kW).
     */
    @WithDefault("slimmelezer/sensor/power_produced/state")
    String gridProducedTopic();

    /**
     * SMART mode: below this battery level (%) it always charges (like PV_AND_MIN).
     */
    @WithDefault("40")
    int smartMinSoc();

    /**
     * SMART mode: grid import in W allowed at (and below) {@link #smartMinSoc()}. Measured like the
     * PV_ONLY thresholds, i.e. as the grid power with the car charging at minimum current on 1 phase.
     */
    @WithDefault("1000")
    int smartGridImportLow();

    /**
     * SMART mode: grid import in W allowed at 100%. Linear between {@link #smartGridImportLow()} and this.
     */
    @WithDefault("0")
    int smartGridImportHigh();

    /**
     * SMART mode: charging stops when the grid import (with the car at minimum current) exceeds the
     * allowed import by more than this many W, for {@link #stopDelay()}.
     */
    @WithDefault("500")
    int smartStopHysteresis();

}
