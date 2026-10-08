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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class MqttHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(30);

    private final Vertx vertx;
    private MqttClient mqttClient;
    private volatile boolean started = false;
    private volatile boolean shuttingDown = false;
    private volatile boolean restarting = false;
    private final MqttConfig mqttConfig;
    private final ObjectMapper objectMapper;

    /**
     * Publish health. Updated from vert.x event-loop callbacks, read from the health endpoint.
     */
    private final AtomicInteger consecutivePublishFailures = new AtomicInteger();
    private volatile long lastPublishAttemptMs = 0;
    private volatile long lastPublishSuccessMs = System.currentTimeMillis();

    private final List<Subscriber> listeners = new ArrayList<>();

    public interface Listener {
        void handleMessage(String topic, Matcher matchedTopic, String payload);
    }

    private record Subscriber(Pattern pattern, String mqttPattern, Listener listener) {
    }

    /**
     * Snapshot of the MQTT connection state for the health endpoint.
     */
    public record Status(boolean enabled,
                         boolean started,
                         boolean connected,
                         int consecutivePublishFailures,
                         long msSinceLastPublishAttempt,
                         long msSinceLastPublishSuccess) {
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
        shuttingDown = false;
        MqttClientOptions mqttClientOptions = new MqttClientOptions()
                .setMaxInflightQueue(200)
                .setAckTimeout(mqttConfig.ackTimeoutSeconds())
                .setKeepAliveInterval(mqttConfig.keepAliveSeconds())
                .setAutoKeepAlive(true);
        mqttClientOptions.setAutoAck(true);
        mqttClient = MqttClient.create(vertx, mqttClientOptions);

        connectMqtt(() -> {
            LOG.info("MQTT ready");
            subscribe();
            consecutivePublishFailures.set(0);
            lastPublishSuccessMs = System.currentTimeMillis();
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
                    if (!shuttingDown) {
                        connectMqtt(onConnected);
                    }
                });
            } else {
                LOG.info("MQTT connected");
                onConnected.run();
            }
        });
    }

    public void stop() {
        LOG.info("Stopping");
        shuttingDown = true;
        teardown();
    }

    private void teardown() {
        started = false;
        MqttClient old = mqttClient;
        if (old == null) {
            return;
        }
        // Detach handlers so a late close/exception callback from this (now discarded) client can't
        // tear down the fresh connection created by the following start().
        try {
            old.closeHandler(null);
            old.exceptionHandler(null);
        } catch (Exception e) {
            LOG.debug("Error clearing MQTT handlers", e);
        }
        try {
            if (old.isConnected()) {
                old.disconnect();
            }
        } catch (Exception e) {
            LOG.debug("Error disconnecting MQTT client", e);
        }
    }

    /**
     * Tear down the current connection and build a fresh one after a delay. Used both when the
     * broker closes the socket and when publishes keep failing while the socket looks alive (the
     * "silent" hang seen in production).
     */
    private synchronized void restart() {
        if (shuttingDown) {
            return;
        }
        if (restarting) {
            return;
        }
        restarting = true;
        teardown();
        LOG.info("Restarting MQTT connection in {}s", RECONNECT_DELAY.toSeconds());
        vertx.setTimer(RECONNECT_DELAY.toMillis(), l -> {
            restarting = false;
            if (!shuttingDown) {
                start();
            }
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
        mqttClient.publishCompletionHandler(id -> onPublishSuccess());
        mqttClient.publishCompletionExpirationHandler(id ->
                onPublishFailure(new IllegalStateException("PUBACK timeout for packet " + id)));
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
                LOG.debug("Dispatching to {}", t.mqttPattern);
                try {
                    t.listener.handleMessage(msg.topicName(), m, msg.payload().toString());
                } catch (Exception e) {
                    LOG.warn("Listener for {} failed handling message on {}", t.mqttPattern, msg.topicName(), e);
                }
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
        doPublish(topic, payload, retain);
    }

    public void publish(String topic, Buffer payload) {
        publish(topic, payload, false);
    }

    public void publish(String topic, String payload) {
        doPublish(topic, Buffer.buffer(payload), false);
    }

    private void doPublish(String topic, Buffer payload, boolean retain) {
        lastPublishAttemptMs = System.currentTimeMillis();
        MqttClient client = mqttClient;
        if (client == null) {
            onPublishFailure(new IllegalStateException("MQTT client not initialised"));
            return;
        }
        try {
            client.publish(topic, payload, MqttQoS.AT_LEAST_ONCE, false, retain)
                    .onFailure(this::onPublishFailure);
        } catch (Exception e) {
            // vert.x can throw synchronously (e.g. NPE on its internal context) once the connection
            // has silently gone away - treat it like any other publish failure.
            onPublishFailure(e);
        }
    }

    private void onPublishSuccess() {
        consecutivePublishFailures.set(0);
        lastPublishSuccessMs = System.currentTimeMillis();
    }

    private void onPublishFailure(Throwable t) {
        int failures = consecutivePublishFailures.incrementAndGet();
        if (failures == 1 || failures % 20 == 0) {
            LOG.warn("MQTT publish failed ({} in a row)", failures, t);
        }
        if (failures >= mqttConfig.maxPublishFailures() && !restarting && !shuttingDown) {
            LOG.error("MQTT publish failed {} times in a row, forcing reconnect", failures);
            consecutivePublishFailures.set(0);
            restart();
        }
    }

    public boolean isStarted() {
        return started;
    }

    public Status status() {
        long now = System.currentTimeMillis();
        MqttClient client = mqttClient;
        return new Status(
                mqttConfig.enabled(),
                started,
                client != null && client.isConnected(),
                consecutivePublishFailures.get(),
                lastPublishAttemptMs == 0 ? -1 : now - lastPublishAttemptMs,
                now - lastPublishSuccessMs);
    }
}
