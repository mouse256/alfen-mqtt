import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;

public class MockAlfenDeviceResource implements QuarkusTestResourceLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int PORT = 54741; //TODO find free port
    private MockAlfenDevice mockAlfenDevice;

    @Override
    public Map<String, String> start() {
        LOG.info("Start");
        return Map.of(
                "alfen.devices[1].password", "dummy",
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
        mockAlfenDevice = new MockAlfenDevice(vertx, PORT);
        testInjector.injectIntoFields(mockAlfenDevice,
                new TestInjector.AnnotatedAndMatchesType(InjectMockAlfenDevice.class, MockAlfenDevice.class));
    }
}
