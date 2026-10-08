package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "mqtt")
public interface MqttConfig {
    String host();

    int port();

    boolean enabled();

    String clientId();

    /**
     * Seconds after which an unacknowledged QoS 1 publish is considered expired and its slot in the
     * inflight queue is released. Without this the vert.x MQTT client keeps a stuck message forever;
     * once {@code maxInflightQueue} such messages pile up every publish fails permanently and the
     * application hangs.
     */
    @WithDefault("30")
    int ackTimeoutSeconds();

    /**
     * MQTT keep-alive interval in seconds. A low value makes a silently dead broker connection
     * surface (and trigger a reconnect) faster.
     */
    @WithDefault("30")
    int keepAliveSeconds();

    /**
     * Number of consecutive failed publishes (inflight queue full, ack timeout, write error) after
     * which the MQTT connection is torn down and rebuilt.
     */
    @WithDefault("20")
    int maxPublishFailures();
}
