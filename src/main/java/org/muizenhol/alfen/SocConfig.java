package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * Car battery (state of charge) estimation. The charger itself doesn't know the car's battery
 * level, so it is estimated from the last level reported by the car (received over MQTT) plus the
 * energy delivered by the charger since then.
 */
@ConfigMapping(prefix = "soc")
public interface SocConfig {

    /**
     * Usable battery capacity of the car in kWh. The estimation is only enabled when this is set.
     */
    Optional<Double> batteryCapacity();

    /**
     * MQTT topic carrying the battery level reported by the car (payload: percentage). Published by
     * another tool (e.g. a Home Assistant automation from the car integration); preferably retained.
     */
    @WithDefault("skoda/enyaq/battery_percentage")
    String carSocTopic();

    /**
     * Fraction of the energy delivered by the charger that ends up in the battery (charging losses).
     */
    @WithDefault("0.9")
    double efficiency();

    /**
     * How often the estimate is recalculated and published.
     */
    @WithDefault("PT10S")
    Duration interval();
}
