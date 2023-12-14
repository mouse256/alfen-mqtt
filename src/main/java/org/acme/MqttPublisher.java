package org.acme;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.acme.data.PropertyParsed;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@ApplicationScoped
public class MqttPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final AlfenController controller;
    private final Vertx vertx;
    private final List<String> devices;
    private final MqttConfig mqttConfig;

    @Inject
    @Channel("properties")
    private Emitter<List<?>> emitter;

    public MqttPublisher(AlfenConfig alfenConfig, Vertx vertx, AlfenController controller, MqttConfig mqttConfig) {
        devices = alfenConfig.devices().stream().map(AlfenConfig.Device::name).collect(Collectors.toList());
        this.vertx = vertx;
        this.controller = controller;
        this.mqttConfig = mqttConfig;
    }

    public void onStart(@Observes StartupEvent startupEvent) {
        if (!mqttConfig.enabled()) {
            LOG.warn("MQTT is disabled");
            return;
        }
        LOG.info("Startup: devices: {}", devices);
        vertx.setPeriodic(0, Duration.ofSeconds(5).toMillis(),
                e -> devices.forEach(
                        device -> mqttConfig.categories().forEach(
                                category -> poll(device, category))));
    }

    private void poll(String device, String category) {
        LOG.debug("Polling device {} (category {})", device, category);
        try {
            List<PropertyParsed> properties = controller.getProperties(device, category);
            LOG.debug("Got {} properties", properties.size());
            send(device, category, properties);
        } catch (Exception e) {
            LOG.warn("Can't fetch category {} for device {}: {}", category, device, e.getMessage());
        }
    }

    private void send(String device, String category, List<PropertyParsed> properties) {
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
}
