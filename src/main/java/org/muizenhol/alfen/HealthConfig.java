package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Thresholds used by {@link HealthResource} to decide whether the application is still doing useful
 * work or has wedged (deadlock / silently broken MQTT connection) and should be restarted.
 */
@ConfigMapping(prefix = "health")
public interface HealthConfig {

    /**
     * Grace period after JVM start during which the liveness check always reports UP, so a slow
     * start-up (broker/charger not reachable yet) does not cause a restart loop.
     */
    @WithDefault("PT1M")
    Duration startupGrace();

    /**
     * A modbus poll loop that has not completed a single successful read within this window is
     * considered stale.
     */
    @WithDefault("PT2M")
    Duration modbusPollStaleLimit();

    /**
     * A single modbus poll iteration that has been running (blocking the worker) for longer than
     * this is considered a deadlock/hang.
     */
    @WithDefault("PT2M")
    Duration modbusPollStallLimit();

    /**
     * When publishes are still being attempted but no broker acknowledgement has been seen within
     * this window, the MQTT side is considered wedged.
     */
    @WithDefault("PT2M")
    Duration mqttPublishStaleLimit();
}
