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
     * Total grid-import budget in W. The FAST mode never tries to import more than this,
     * and the spike guard triggers when import exceeds this (plus {@link #spikePercent()}).
     */
    @WithDefault("7400")
    int maxGridPower();

    /**
     * Percentage above {@link #maxGridPower()} that triggers an immediate stop (no debounce).
     */
    @WithDefault("10")
    int spikePercent();

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
     * In PV_ONLY mode: surplus (export) in W above which charging may start.
     */
    @WithDefault("500")
    int solarStartPower();

    /**
     * In PV_ONLY mode: import in W above which charging stops.
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

}
