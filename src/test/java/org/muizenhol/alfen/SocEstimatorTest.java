package org.muizenhol.alfen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SocEstimatorTest {

    private static final String CHARGER_NAME = "dummy";
    private static final int SOCKET = 1;
    private static final String TOPIC = "skoda/test/battery";

    AlfenModbusClient client;
    MqttHandler mqttHandler;
    SocEstimator estimator;
    MqttHandler.Listener listener;
    Pattern pattern;
    Double energyWh;

    @BeforeEach
    public void beforeEach() {
        client = Mockito.mock(AlfenModbusClient.class);
        mqttHandler = Mockito.mock(MqttHandler.class);
        energyWh = 10_000.0;
        doAnswer(i -> Optional.ofNullable(energyWh)).when(client).getSocketRealEnergyDeliveredSum(SOCKET);
        SocConfig config = new SocConfig() {
            public Optional<Double> batteryCapacity() {
                return Optional.of(50.0);
            }

            public String carSocTopic() {
                return TOPIC;
            }

            public double efficiency() {
                return 0.9;
            }

            public Duration interval() {
                return Duration.ofSeconds(10);
            }
        };
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        estimator = new SocEstimator(null, client, CHARGER_NAME, SOCKET, mqttHandler, config, 50.0, () -> now);

        ArgumentCaptor<MqttHandler.Listener> l = ArgumentCaptor.forClass(MqttHandler.Listener.class);
        ArgumentCaptor<Pattern> p = ArgumentCaptor.forClass(Pattern.class);
        verify(mqttHandler).register(p.capture(), eq(TOPIC), l.capture());
        listener = l.getValue();
        pattern = p.getValue();
    }

    private void carSoc(String payload) {
        Matcher m = pattern.matcher(TOPIC);
        assertThat(m.matches(), equalTo(true));
        listener.handleMessage(TOPIC, m, payload);
    }

    private SocEstimator.SocStatus lastPublished() {
        ArgumentCaptor<Object> ac = ArgumentCaptor.forClass(Object.class);
        verify(mqttHandler, atLeastOnce()).publishJson(eq("alfen/car/dummy/1/soc_estimate"), ac.capture());
        return (SocEstimator.SocStatus) ac.getValue();
    }

    @Test
    public void testNothingPublishedWithoutCarLevel() {
        estimator.update();
        verify(mqttHandler, never()).publishJson(any(), any());
    }

    @Test
    public void testEstimateAddsDeliveredEnergy() {
        carSoc("40");
        assertThat(lastPublished().estimate(), closeTo(40, 0.01));

        // 5 kWh delivered * 0.9 / 50 kWh = +9%
        energyWh += 5_000;
        estimator.update();
        SocEstimator.SocStatus status = lastPublished();
        assertThat(status.estimate(), closeTo(49, 0.01));
        assertThat(status.carSoc(), closeTo(40, 0.01));
        assertThat(status.energySinceWh(), closeTo(5_000, 0.01));
    }

    @Test
    public void testNewCarLevelResetsEstimate() {
        carSoc("40");
        energyWh += 5_000;
        estimator.update();

        carSoc("47.5");
        assertThat(lastPublished().estimate(), closeTo(47.5, 0.01));
        energyWh += 1_000;
        estimator.update();
        assertThat(lastPublished().estimate(), closeTo(47.5 + 1.8, 0.01));
    }

    @Test
    public void testCappedAt100() {
        carSoc("98");
        energyWh += 10_000;
        estimator.update();
        assertThat(lastPublished().estimate(), closeTo(100, 0.01));
    }

    @Test
    public void testCarLevelBeforeFirstMeasurement() {
        energyWh = null;
        carSoc("60");
        assertThat(lastPublished().estimate(), closeTo(60, 0.01));

        // first measurement becomes the anchor
        energyWh = 20_000.0;
        estimator.update();
        assertThat(lastPublished().estimate(), closeTo(60, 0.01));
        energyWh += 500;
        estimator.update();
        assertThat(lastPublished().estimate(), closeTo(60.9, 0.01));
    }

    @Test
    public void testInvalidPayloadIgnored() {
        carSoc("unavailable");
        estimator.update();
        verify(mqttHandler, never()).publishJson(any(), any());
    }
}
