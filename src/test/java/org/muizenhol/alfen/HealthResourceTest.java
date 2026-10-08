package org.muizenhol.alfen;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(HealthResourceTest.NoGraceProfile.class)
public class HealthResourceTest {

    public static class NoGraceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "auto.startup", "false",
                    "health.startup-grace", "PT0S",
                    "health.modbus-poll-stale-limit", "PT2M",
                    "health.modbus-poll-stall-limit", "PT2M",
                    "health.mqtt-publish-stale-limit", "PT2M");
        }
    }

    @InjectMock
    AlfenModbus alfenModbus;

    @InjectMock
    MqttHandler mqttHandler;

    private AlfenModbusClient healthyClient;

    @BeforeEach
    void setup() {
        healthyClient = Mockito.mock(AlfenModbusClient.class);
        Mockito.when(healthyClient.getName()).thenReturn("alfen1");
        Mockito.when(healthyClient.isPolling()).thenReturn(true);
        Mockito.when(healthyClient.pollStallMs()).thenReturn(0L);
        Mockito.when(healthyClient.msSinceLastSuccessfulPoll()).thenReturn(1_000L);
        Mockito.when(alfenModbus.getClients()).thenReturn(List.of(healthyClient));

        Mockito.when(mqttHandler.status()).thenReturn(
                new MqttHandler.Status(true, true, true, 0, 1_000, 1_000));
    }

    @Test
    void healthy() {
        when().get("/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void modbusPollDeadlocked() {
        Mockito.when(healthyClient.pollStallMs()).thenReturn(5 * 60_000L);

        when().get("/health/live")
                .then().statusCode(503)
                .body("status", equalTo("DOWN"));
    }

    @Test
    void modbusPollStale() {
        Mockito.when(healthyClient.msSinceLastSuccessfulPoll()).thenReturn(5 * 60_000L);

        when().get("/health/live")
                .then().statusCode(503)
                .body("status", equalTo("DOWN"));
    }

    @Test
    void mqttSilentlyBroken() {
        // still trying to publish, but no broker ack for a long time
        Mockito.when(mqttHandler.status()).thenReturn(
                new MqttHandler.Status(true, true, true, 37, 1_000, 5 * 60_000L));

        when().get("/health/live")
                .then().statusCode(503)
                .body("status", equalTo("DOWN"));
    }

    @Test
    void mqttDisconnected() {
        Mockito.when(mqttHandler.status()).thenReturn(
                new MqttHandler.Status(true, false, false, 0, -1, 1_000));

        when().get("/health/live")
                .then().statusCode(503)
                .body("status", equalTo("DOWN"));
    }

    @Test
    void mqttIdleIsHealthy() {
        // nothing published recently -> "no ack" must NOT be treated as a failure
        Mockito.when(mqttHandler.status()).thenReturn(
                new MqttHandler.Status(true, true, true, 0, 10 * 60_000L, 10 * 60_000L));

        when().get("/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }
}
