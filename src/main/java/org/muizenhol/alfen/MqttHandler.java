package org.muizenhol.alfen;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MqttHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Vertx vertx;
    private MqttClient mqttClient;
    private volatile boolean started = false;
    private MqttConfig mqttConfig;

    private final List<Subscriber> listeners = new ArrayList<>();

    public interface Listener {
        void handleMessage(String topic, Matcher matchedTopic, String payload);
    }

    private record Subscriber(Pattern pattern, String mqttTopic, Listener listener) {
    }

    public MqttHandler(Vertx vertx, MqttConfig mqttConfig) {
        this.vertx = vertx;
        this.mqttConfig = mqttConfig;
    }


    public void start() {
        LOG.info("Verticle starting");
        if (!mqttConfig.enabled()) {
            LOG.warn("MQTT not enabled");
            return;
        }
        MqttClientOptions mqttClientOptions = new MqttClientOptions()
                .setMaxInflightQueue(200);
        mqttClientOptions.setAutoAck(true);
        mqttClient = MqttClient.create(vertx, mqttClientOptions);

        connectMqtt(() -> {
            LOG.info("MQTT ready");
            started = true;
            subscribe();
        });
        mqttClient.closeHandler(v -> {
            LOG.info("Mqtt closed, restart");
            restart();
        });
        mqttClient.exceptionHandler(ex -> {
            LOG.warn("Exception", ex);
            restart();
        });
    }

    private void connectMqtt(Runnable onConnected) {
        mqttClient.connect(mqttConfig.port(), mqttConfig.host(), ar -> {
            if (ar.failed()) {
                LOG.warn("MQTT connection failed, retrying in 60 s", ar.cause());
                vertx.setTimer(Duration.ofSeconds(60).toMillis(), l -> {
                    connectMqtt(onConnected);
                });
            } else {
                LOG.info("MQTT connected");
                onConnected.run();
            }
        });
    }

    public void stop() {
        LOG.info("Stopping");
        started = false;
        //consumer.unregister()
        if (mqttClient.isConnected()) {
            mqttClient.disconnect();
        }
    }

    private void restart() {
        if (!started) {
            LOG.warn("Cannot restart, not yet running");
            return;
        }
        stop();
        LOG.info("Restarting in 30s");
        vertx.setTimer(Duration.ofSeconds(30).toMillis(), l -> {
            start();
        });
    }

    public void register(Pattern topicPattern, String mqttPattern, Listener listener) {
        mqttClient.subscribe(mqttPattern, MqttQoS.AT_LEAST_ONCE.value());
        LOG.info("Subscribing to topic {}", mqttPattern);
        listeners.add(new Subscriber(topicPattern, mqttPattern, listener));
    }

    private void subscribe() {
        mqttClient.publishHandler(this::handleMsg);
        listeners.forEach(l -> {
            LOG.info("Re-Subscribing to topic {}", l.mqttTopic);
            mqttClient.subscribe(
                    l.mqttTopic,
                    MqttQoS.AT_MOST_ONCE.value()
            );
        });
    }

    private void handleMsg(MqttPublishMessage msg) {
        handleMsgWitchAck(msg);
    }

    private void handleMsgWitchAck(MqttPublishMessage msg) {
        LOG.debug("Got msg on {}", msg.topicName());

        listeners.forEach(t -> {
            Matcher m = t.pattern.matcher(msg.topicName());
            if (m.matches()) {
                LOG.warn("Dispatching to {}", t.mqttTopic);
                t.listener.handleMessage(msg.topicName(), m, msg.payload().toString());
            }
        });

    }
}
