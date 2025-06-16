package org.muizenhol.alfen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
    private final MqttConfig mqttConfig;
    private final ObjectMapper objectMapper;

    private final List<Subscriber> listeners = new ArrayList<>();

    public interface Listener {
        void handleMessage(String topic, Matcher matchedTopic, String payload);
    }

    private record Subscriber(Pattern pattern, String mqttPattern, Listener listener) {
    }

    public MqttHandler(Vertx vertx, MqttConfig mqttConfig, ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.mqttConfig = mqttConfig;
        this.objectMapper = objectMapper;
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
            subscribe();
            started = true;
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

    public synchronized void register(Pattern topicPattern, String mqttPattern, Listener listener) {
        LOG.info("Subscribing to topic {}", mqttPattern);
        listeners.add(new Subscriber(topicPattern, mqttPattern, listener));
        if (started) {
            mqttClient.subscribe(mqttPattern, MqttQoS.AT_LEAST_ONCE.value());
        }
        // else will be done in subscribe call
    }

    private synchronized void subscribe() {
        mqttClient.publishHandler(this::handleMsg);
        listeners.forEach(l -> {
            LOG.info("Re-Subscribing to topic {}", l.mqttPattern);
            mqttClient.subscribe(
                    l.mqttPattern,
                    MqttQoS.AT_LEAST_ONCE.value(),
                    ar -> LOG.info("mqtt subscribe result for {}: {}", l.mqttPattern, ar)
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
                LOG.warn("Dispatching to {}", t.mqttPattern);
                t.listener.handleMessage(msg.topicName(), m, msg.payload().toString());
            }
        });
    }

    public void publishJson(String topic, Object payload) {
        publishJson(topic, payload, false);
    }

    public void publishJson(String topic, Object payload, boolean retain) {
        try {
            Buffer buffer = Buffer.buffer(objectMapper.writer().writeValueAsBytes(payload));
            publish(topic, buffer, retain);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void publish(String topic, Buffer payload, boolean retain) {
        mqttClient.publish(topic, payload, MqttQoS.AT_LEAST_ONCE, false, retain);
    }

    public void publish(String topic, Buffer payload) {
        publish(topic, payload, false);
    }

    public void publish(String topic, String payload) {
        mqttClient.publish(topic, Buffer.buffer(payload), MqttQoS.AT_LEAST_ONCE, false, false);
    }

    public boolean isStarted() {
        return started;
    }
}
