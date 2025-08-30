package org.muizenhol.alfen;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.muizenhol.alfen.data.Evcc;
import org.muizenhol.alfen.data.PropertyParsed;
import org.muizenhol.homeassistant.discovery.Discovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@ApplicationScoped
public class MqttPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final MqttConfig mqttConfig;
    private final MqttHandler mqttHandler;

//    @Inject
//    @Channel("properties")
//    Emitter<Object> emitter;


    public MqttPublisher(AlfenConfig alfenConfig, AlfenController controller, MqttConfig mqttConfig, MqttHandler mqttHandler) {
        this.mqttConfig = mqttConfig;
        this.mqttHandler = mqttHandler;
    }

    public void send(String device, String category, List<PropertyParsed> properties) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        String topic = "alfen/properties/" + device.toLowerCase() + "/category/" + category;
        LOG.debug("Sending to topic: {}", topic);

//        CompletableFuture<Void> future = new CompletableFuture<>();
//        Message<List<PropertyParsed>> msg = MqttMessage.of(topic, properties, MqttQoS.AT_LEAST_ONCE)
//                .withAck(() -> {
//                    future.complete(null);
//                    return CompletableFuture.completedFuture(null);
//                }).withNack(reason -> {
//                    future.completeExceptionally(reason);
//                    return CompletableFuture.completedFuture(null);
//                });
//        future.thenRun(() -> {
//            LOG.debug("Msg acked");
//        }).exceptionally(ex -> {
//            LOG.warn("Error sending msg to {}", topic, ex);
//            return null;
//        });
//        emitter.send(msg);
        mqttHandler.publishJson(topic, properties);

    }

    public void sendModbus(String name, String group, Map<Integer, Object> values, int addr) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        //Having ints as keys in json makes the parsing hard on some tools/libraries.
        //So prefix with "S" from start to make them a string.
        Map<String, Object> values2 = values.entrySet().stream()
                .collect(Collectors.toMap(i -> "S" + i.getKey(), Map.Entry::getValue));
        String topic = "alfen/modbus/state/" + name + "/" + addr + "/" + group;

        //MqttMessage<Object> msg = MqttMessage.of(topic, values2, MqttQoS.AT_LEAST_ONCE);
        //emitter.send(msg);
        mqttHandler.publishJson(topic, values2);
    }

    public void sendModbusEvcc(String name, int addr, Evcc.Charger charger) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        String topic = "alfen/evcc/status/" + name + "/" + addr;
        //MqttMessage<Object> msg = MqttMessage.of(topic, charger, MqttQoS.AT_LEAST_ONCE);
        //emitter.send(msg);
        mqttHandler.publishJson(topic, charger);
    }

    @ConfigProperty(name = "discovery.uuid")
    String discoveryUuid;

    public void sendDiscovery(Discovery discovery) {
        if (!mqttConfig.enabled()) {
            return;
        }
        LOG.info("Sending discovery");

        //DiscoveryHelper discoveryHelper = new DiscoveryHelper();
        //MqttMessage<Object> discoveryMsg = discoveryHelper.genDiscovery(discovery);
        //emitter.send(discoveryMsg);

        String topic = "homeassistant/device/" + mqttConfig.clientId() + "/" + discovery.device().identifiers() + "/config";
        mqttHandler.publishJson(topic, discovery, true);
    }
}
