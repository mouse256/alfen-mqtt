package org.muizenhol.alfen;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.muizenhol.alfen.mqtt.MqttTestResource;
import org.muizenhol.alfen.mqtt.TestMqttServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(value = MqttTestResource.class, restrictToAnnotatedClass = true)
public class MqttHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    MqttHandler mqttHandler;

    @Inject
    MqttConfig mqttConfig;

    @Inject
    Vertx vertx;

    @MqttTestResource.Server
    TestMqttServer testMqttServer;

    @Test
    public void test1() {

        String topic1 = "/test/topic1";
        String topic2 = "/test/topic2";
        String topic3 = "/test/topic3";
        mqttHandler.start();
        Map<String, Integer> resultMap = Collections.synchronizedMap(new HashMap<>());
        MqttHandler.Listener l = new MqttHandler.Listener() {
            @Override
            public void handleMessage(String topic, Matcher matchedTopic, String payload) {
                LOG.info("Received message on: {}", topic);
                resultMap.compute(topic, (s, count) -> (count == null ? 1 : count + 1));
            }
        } ;



        //register before starting
        mqttHandler.register(Pattern.compile(topic1), topic1, l);

        //start and immediately register
        MqttClient client = MqttClient.create(vertx);
        mqttHandler.register(Pattern.compile(topic2), topic2, l);

        eventually(() -> mqttHandler.isStarted());


        client.connect(mqttConfig.port(), mqttConfig.host()).toCompletionStage().toCompletableFuture().join();

        LOG.info("publish 1");
        client.publish(topic1, Buffer.buffer("test1"), MqttQoS.AT_LEAST_ONCE, false, false);
        client.publish(topic2, Buffer.buffer("test1"), MqttQoS.AT_LEAST_ONCE, false, false);
        client.publish(topic3, Buffer.buffer("test1"), MqttQoS.AT_LEAST_ONCE, false, false);

        eventually(() -> {
            LOG.debug("resultmap: {}", resultMap);
            return resultMap.size() == 2;
        });
        assertThat(resultMap.get(topic1), equalTo(1));
        assertThat(resultMap.get(topic2), equalTo(1));

        //late register
        mqttHandler.register(Pattern.compile(topic3), topic3, l);
        LOG.info("publish 2");
        client.publish(topic1, Buffer.buffer("test2"), MqttQoS.AT_LEAST_ONCE, false, false);
        client.publish(topic2, Buffer.buffer("test2"), MqttQoS.AT_LEAST_ONCE, false, false);
        client.publish(topic3, Buffer.buffer("test2"), MqttQoS.AT_LEAST_ONCE, false, false);

        eventually(() -> {
            LOG.debug("resultmap: {}", resultMap);
            return resultMap.size() == 3;
        });
        assertThat(resultMap.get(topic1), equalTo(2));
        assertThat(resultMap.get(topic2), equalTo(2));
        assertThat(resultMap.get(topic3), equalTo(1));
    }

    private void eventually(Supplier<Boolean> supplier) {
        for (int i = 0; i < 10; i++) {
            if (supplier.get()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("test failed waiting");
    }
}
