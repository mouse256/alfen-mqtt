package org.muizenhol.alfen;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.util.Map;

public class MockAlfenHttpDeviceResource implements QuarkusTestResourceLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int PORT = 54741; //TODO find free port
    private MockAlfenHttpDevice mockAlfenDevice;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface InjectMockAlfenDevice {
    }

    @Override
    public Map<String, String> start() {
        LOG.info("Start");
        return Map.of(
                "alfen.devices[1].username", "dummy",
                "alfen.devices[1].password", "dummy",
                "alfen.devices[1].type", "http",
                "alfen.devices[1].endpoint", "http://127.0.0.7:" + PORT);
    }

    @Override
    public void stop() {
        try {
            mockAlfenDevice.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void inject(TestInjector testInjector) {
        LOG.info("Inject");
        Vertx vertx = Vertx.vertx();
        mockAlfenDevice = new MockAlfenHttpDevice(vertx, PORT);
        testInjector.injectIntoFields(mockAlfenDevice,
                new TestInjector.AnnotatedAndMatchesType(InjectMockAlfenDevice.class, MockAlfenHttpDevice.class));
    }
}
