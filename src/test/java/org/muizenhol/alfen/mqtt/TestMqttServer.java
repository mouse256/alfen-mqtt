package org.muizenhol.alfen.mqtt;

import io.netty.handler.codec.mqtt.MqttProperties;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.codes.MqttSubAckReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class TestMqttServer {
    private final Vertx vertx;
    private MqttServer mqttServer;
    private final List<Item> subscribers = Collections.synchronizedList(new ArrayList<>());
    private final List<MqttEndpoint> endpoints = Collections.synchronizedList(new ArrayList<>());

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private record Item(Pattern topicPattern, String topic, MqttEndpoint endpoint) {
    }

    public TestMqttServer(Vertx vertx) {
        this.vertx = vertx;
    }

    public void start(int port, Handler<AsyncResult<MqttServer>> listenHandler) {
        MqttServerOptions options = new MqttServerOptions().setPort(port);
        mqttServer = MqttServer.create(vertx, options);

        mqttServer.endpointHandler(endpoint -> {
            endpoints.add(endpoint);
            endpoint.accept(false);

            // Handle subscriptions
            endpoint.subscribeHandler(subscribe -> {
                List<MqttSubAckReasonCode> reasonCodes = new ArrayList<>();
                for (MqttTopicSubscription sub : subscribe.topicSubscriptions()) {
                    String topic = sub.topicName();
                    LOG.info("Subscription on topic: {}", topic);
                    subscribers.add(new Item(topicPattern(topic), topic, endpoint));
                    //topicSubscribers
                    //       .computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet())
                    //     .add(endpoint);
                    reasonCodes.add(MqttSubAckReasonCode.qosGranted(sub.qualityOfService()));
                }
                endpoint.subscribeAcknowledge(subscribe.messageId(), reasonCodes, MqttProperties.NO_PROPERTIES);
            });

            // Handle unsubscriptions
            endpoint.unsubscribeHandler(unsubscribe -> {
//                unsubscribe.topics().forEach(topic ->
//                        topicSubscribers.computeIfPresent(topic, (k, v) -> {
//                            v.remove(endpoint);
//                            return v.isEmpty() ? null : v;
//                        })
//                );
                LOG.warn("Unsubscribe not implemented");
                endpoint.unsubscribeAcknowledge(unsubscribe.messageId());
            });

            // Handle incoming messages
            endpoint.publishHandler(message -> {
                LOG.info("Incoming msg on {}", message.topicName());
                subscribers.forEach(s -> {
                            if (s.topicPattern.matcher(message.topicName()).matches()) {
                                LOG.info("Sending to handler: {}", s.topic());
                                s.endpoint.publish(
                                        message.topicName(),
                                        message.payload(),
                                        message.qosLevel(),
                                        message.isDup(),
                                        message.isRetain()
                                );
                            }
                        }
                );

            });

            // Cleanup on disconnect
            endpoint.disconnectHandler(v -> {
                        LOG.warn("Disconnect not implemented");
                    }

//                    subscribers.forEach(s -> {
//                        subscribers.remove(endpoint);
//                    });
//                    topicSubscribers.values().forEach(subscribers ->
//                            subscribers.remove(endpoint)
//                    )
            );
        }).listen(listenHandler);
    }

    private Pattern topicPattern(String topic) {
        String tp = topic.replaceAll("\\+", "[^/]+").replaceAll("#", ".+");
        LOG.info("Topic: {} -> {}", topic, tp);
        return Pattern.compile(tp);
    }

    public void stop() {
        if (mqttServer != null) {
            mqttServer.close();
            subscribers.clear();
        }
    }

    public void cleanup() {
        LOG.info("Cleanup");
        try {
            endpoints.forEach(MqttEndpoint::close);
        } catch (Exception e) {
            //don't care
        }
        endpoints.clear();
        subscribers.clear();
    }
}
