package org.muizenhol.alfen;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import org.muizenhol.homeassistant.discovery.Discovery;

public class DiscoveryHelper {
    public static String DEVICE_NAME = "alfen-mqtt";

    public MqttMessage<Object> genDiscovery(Discovery discovery) {
        return MqttMessage.of("homeassistant/device/" + DEVICE_NAME + "/" + discovery.device().identifiers() + "/config", discovery, MqttQoS.AT_LEAST_ONCE, true);
    }
}
