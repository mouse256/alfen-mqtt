package org.muizenhol.homeassistant;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;

public class DiscoveryHelper {
    public static String DEVICE_NAME = "alfen-mqtt";

    public MqttMessage<Object> genDiscovery(Discovery discovery, String id) {
        MqttMessage<Object> msg = MqttMessage.of("homeassistant/device/" + DEVICE_NAME + "/" + id + "/config", discovery, MqttQoS.AT_LEAST_ONCE);
        return msg;
    }
}
