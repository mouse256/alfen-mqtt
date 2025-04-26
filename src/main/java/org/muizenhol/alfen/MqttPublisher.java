package org.muizenhol.alfen;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.muizenhol.alfen.data.Evcc;
import org.muizenhol.alfen.data.PropertyParsed;
import org.muizenhol.homeassistant.Discovery;
import org.muizenhol.homeassistant.DiscoveryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@ApplicationScoped
public class MqttPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final MqttConfig mqttConfig;

    @Inject
    @Channel("properties")
    Emitter<Object> emitter;

    public MqttPublisher(AlfenConfig alfenConfig, AlfenController controller, MqttConfig mqttConfig) {
        this.mqttConfig = mqttConfig;
    }

    public void send(String device, String category, List<PropertyParsed> properties) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        String topic = "alfen/properties/" + device.toLowerCase() + "/category/" + category;
        LOG.debug("Sending to topic: {}", topic);

        CompletableFuture<Void> future = new CompletableFuture<>();
        Message<List<PropertyParsed>> msg = MqttMessage.of(topic, properties, MqttQoS.AT_LEAST_ONCE)
                .withAck(() -> {
                    future.complete(null);
                    return CompletableFuture.completedFuture(null);
                }).withNack(reason -> {
                    future.completeExceptionally(reason);
                    return CompletableFuture.completedFuture(null);
                });
        future.thenRun(() -> {
            LOG.debug("Msg acked");
        }).exceptionally(ex -> {
            LOG.warn("Error sending msg to {}", topic, ex);
            return null;
        });
        emitter.send(msg);
    }

    public void sendModbus(String name, String group, Map<?, Object> values, int addr) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        MqttMessage<Object> msg = MqttMessage.of("alfen/modbus/state/" + name + "/" + addr + "/" + group, values, MqttQoS.AT_LEAST_ONCE);
        emitter.send(msg);
    }

    public void sendModbusEvcc(String name, int addr, Evcc.Charger charger) {
        if (!mqttConfig.enabled()) {
            LOG.debug("MQTT is disabled");
            return;
        }
        MqttMessage<Object> msg = MqttMessage.of("alfen/evcc/status/" + name + "/" + addr, charger, MqttQoS.AT_LEAST_ONCE);
        emitter.send(msg);
    }

    @ConfigProperty(name = "discovery.uuid")
    String discoveryUuid;

    public void sendDiscovery(@Observes StartupEvent startupEvent) {
        if (!mqttConfig.enabled()) {
            return;
        }
        LOG.info("Sending discovery");
        DiscoveryHelper discoveryHelper = new DiscoveryHelper();

        Map<String, Discovery.Component> components = ModbusConst.SOCKET_MEASUREMENT.items().stream()
                .filter(i -> i.discoveryInfo() != null)
                .map(i -> new Discovery.Component(
                        i.name(),
                        "socket_measurement_" + i.start(),
                        Discovery.Component.Platform.SENSOR,
                        i.discoveryInfo().deviceClass(),
                        i.discoveryInfo().stateClass(),
                        i.discoveryInfo().unit(),
                        i.discoveryInfo().precision(),
                        "{{ value_json.S" + i.start() + " }}"
                )).collect(Collectors.toMap(Discovery.Component::uniqueId, i -> i));
        Discovery discovery = new Discovery(
                new Discovery.Device(
                        discoveryUuid,
                        "mouse256",
                        "alfen",
                        "alfen-mqtt"
                )
                , new Discovery.Origin(
                "alfen-mqtt"
        ),
                "alfen/modbus/state/alfen1/1/socket_measurement",
                components
        );
        MqttMessage<Object> discoveryMsg = discoveryHelper.genDiscovery(discovery, discoveryUuid);
        emitter.send(discoveryMsg);
    }
}
