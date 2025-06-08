package org.muizenhol.alfen.mqtt;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.util.Map;

public class MqttTestResource implements QuarkusTestResourceLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int PORT = 5505; //TODO find free port
    private TestMqttServer mqttServer;

    @Override
    public Map<String, String> start() {
        LOG.info("Start");
        Promise<Void> promise = Promise.promise();
        mqttServer = new TestMqttServer(Vertx.vertx());
        mqttServer.start(PORT, ar -> {
            if (ar.succeeded()) {
                promise.complete();
            } else {
                promise.fail(ar.cause());
            }
        });
        promise.future().toCompletionStage().toCompletableFuture().join();
        return Map.of(
                "mqtt.enabled", "true",
                "mqtt.host", "127.0.0.1",
                "mqtt.port", Integer.toString(PORT));
    }

    @Override
    public void stop() {
        mqttServer.stop();
    }


    @Override
    public void inject(TestInjector testInjector) {
        LOG.info("Inject");

        testInjector.injectIntoFields(mqttServer,
                new TestInjector.AnnotatedAndMatchesType(Server.class, TestMqttServer.class));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Server {
    }
}

