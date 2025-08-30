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

public class ModbusTestResource implements QuarkusTestResourceLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int PORT = 5502; //TODO find free port
    private MockAlfenModbusDevice mockAlfenDevice;

    @Override
    public Map<String, String> start() {
        LOG.info("Start");
        Vertx vertx = Vertx.vertx();
        mockAlfenDevice = new MockAlfenModbusDevice(vertx, PORT);
        return Map.of(
                "alfen.devices[1].name", "modbusMock",
                "alfen.devices[1].type", "modbus",
                "alfen.devices[1].endpoint", "127.0.0.1",
                "alfen.devices[1].port", Integer.toString(PORT));
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

        testInjector.injectIntoFields(mockAlfenDevice,
                new TestInjector.AnnotatedAndMatchesType(InjectMock.class, MockAlfenModbusDevice.class));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface InjectMock {
    }
}
