package org.acme;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "mqtt")
public interface MqttConfig {
    String host();

    int port();

    boolean enabled();

    List<String> categories();
}