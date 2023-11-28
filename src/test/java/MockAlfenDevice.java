import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class MockAlfenDevice implements QuarkusTestResourceLifecycleManager {
    @Override
    public Map<String, String> start() {
        return Map.of("alfen.password", "dummy");
    }

    @Override
    public void stop() {

    }
}
