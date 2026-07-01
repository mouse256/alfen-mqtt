package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AlfenModbusWriterTest {

    private record Listener(MqttHandler.Listener l, Pattern p) {
    }

    AlfenModbusClient client;
    MqttHandler mqttHandler;
    Vertx vertx;
    Listener listenerSet;
    Listener listenerPowerConsumed;
    Listener listenerPowerProduced;
    AlfenModbusWriter writer;
    WriterConfig config;
    private static final String CHARGER_NAME = "dummy";
    private static final int SOCKET = 25;
    private Integer chargerPowerConsumed;
    private Instant now;

    @BeforeEach
    public void beforeEach() {
        client = Mockito.mock(AlfenModbusClient.class);
        mqttHandler = Mockito.mock(MqttHandler.class);
        vertx = Mockito.mock(Vertx.class);
        chargerPowerConsumed = 0;
        now = Instant.parse("2026-01-01T00:00:00Z");
        Supplier<Instant> clock = () -> now;
        config = testConfig();

        writer = new AlfenModbusWriter(null, client, CHARGER_NAME, SOCKET, mqttHandler, config, clock);

        listenerSet = registerMock("alfen/set/+/+/+");
        listenerPowerConsumed = registerMock(config.gridConsumedTopic());
        listenerPowerProduced = registerMock(config.gridProducedTopic());

        doAnswer(invocation -> Optional.ofNullable(chargerPowerConsumed)).when(client).getSocketRealPowerSum(SOCKET);
    }

    private WriterConfig testConfig() {
        return new WriterConfig() {
            public boolean enabled() {
                return false;
            }

            public Duration interval() {
                return Duration.ofMillis(100);
            }

            public int minCurrent() {
                return 6;
            }

            public int maxCurrent() {
                return 16;
            }

            public int maxGridPower() {
                return 7400;
            }

            public int spikePercent() {
                return 10;
            }

            public Duration startDelay() {
                return Duration.ofMinutes(2);
            }

            public Duration stopDelay() {
                return Duration.ofMinutes(3);
            }

            public Duration cooldown() {
                return Duration.ofMinutes(5);
            }

            public Duration phaseSwitchDelay() {
                return Duration.ofMinutes(5);
            }

            public int solarStartPower() {
                return 500;
            }

            public int solarStopPower() {
                return 1000;
            }

            public Duration inputTimeout() {
                return Duration.ofMinutes(1);
            }

            public String gridConsumedTopic() {
                return "slimmelezer/sensor/power_consumed/state";
            }

            public String gridProducedTopic() {
                return "slimmelezer/sensor/power_produced/state";
            }
        };
    }

    private Listener registerMock(String pattern) {
        ArgumentCaptor<MqttHandler.Listener> argumentCaptor = ArgumentCaptor.forClass(MqttHandler.Listener.class);
        ArgumentCaptor<Pattern> argumentCaptor2 = ArgumentCaptor.forClass(Pattern.class);
        verify(mqttHandler).register(argumentCaptor2.capture(), eq(pattern), argumentCaptor.capture());
        return new Listener(argumentCaptor.getValue(), argumentCaptor2.getValue());
    }

    private void setMode(String mode) {
        String topic = "alfen/set/" + CHARGER_NAME + "/" + SOCKET + "/mode";
        Matcher m = listenerSet.p.matcher(topic);
        if (!m.matches()) {
            throw new IllegalStateException("no match");
        }
        listenerSet.l.handleMessage(topic, m, mode);
    }

    /**
     * Publish a net grid situation. {@code netExport} > 0 means power is being returned to the grid
     * (solar surplus), < 0 means power is imported from the grid.
     */
    private void grid(int netExport) {
        int produced = Math.max(0, netExport);
        int consumed = Math.max(0, -netExport);
        publish(listenerPowerProduced, config.gridProducedTopic(), produced / 1000.);
        publish(listenerPowerConsumed, config.gridConsumedTopic(), consumed / 1000.);
    }

    private void publish(Listener listener, String topic, double kw) {
        Matcher m = listener.p.matcher(topic);
        if (!m.matches()) {
            throw new IllegalStateException("no match for " + topic);
        }
        listener.l.handleMessage(topic, m, Double.toString(kw));
    }

    private void advance(Duration d) {
        now = now.plus(d);
    }

    private void tick() {
        clearInvocations(client);
        writer.update(1L);
    }

    private void verifySetState(double expectedAmps, int phases) {
        ArgumentCaptor<Float> ac = ArgumentCaptor.forClass(Float.class);
        verify(client).setState(eq(SOCKET), ac.capture(), eq(phases));
        assertThat((double) ac.getValue(), closeTo(expectedAmps, 0.05));
        verify(client, never()).disable(SOCKET);
    }

    private void verifyDisabled() {
        verify(client).disable(SOCKET);
        verify(client, never()).setState(eq(SOCKET), anyFloat(), anyInt());
    }

    @Test
    public void testOff() {
        setMode("OFF");
        grid(2000);
        tick();
        verifyDisabled();
    }

    @Test
    public void testStartDebounce() {
        setMode("PV_AND_MIN");
        grid(0);

        // condition just became satisfied -> not started yet
        tick();
        verifyDisabled();

        // still within start delay
        advance(Duration.ofMinutes(1));
        grid(0);
        tick();
        verifyDisabled();

        // start delay elapsed -> charging at minimum current, single phase
        advance(Duration.ofMinutes(1).plusSeconds(1));
        grid(0);
        tick();
        verifySetState(6, 1);
    }

    @Test
    public void testPvAndMinExcess() {
        startCharging("PV_AND_MIN", 0);

        // plenty of surplus -> charge faster (still single phase below 3-phase threshold)
        grid(2300);
        tick();
        verifySetState(10, 1); // 2300W / 230V = 10A
    }

    @Test
    public void testPvOnlyThresholds() {
        setMode("PV_ONLY");

        // not enough export to start
        grid(400);
        tick();
        verifyDisabled();

        // enough export, but debounce not satisfied
        grid(600);
        tick();
        verifyDisabled();

        advance(Duration.ofMinutes(2).plusSeconds(1));
        grid(600);
        tick();
        verifySetState(6, 1); // started, clamped to min current

        // mild import: not over the solar stop threshold -> keep charging
        grid(-500);
        tick();
        verifySetState(6, 1);

        // import over stop threshold, but stop debounce not satisfied yet
        grid(-1100);
        tick();
        verifySetState(6, 1);

        // stop delay elapsed -> stop
        advance(Duration.ofMinutes(3).plusSeconds(1));
        grid(-1100);
        tick();
        verifyDisabled();
    }

    @Test
    public void testCooldownBlocksRestart() {
        startCharging("PV_ONLY", 600);

        // import beyond stop threshold for the full stop delay -> stop + cooldown
        grid(-1100);
        tick();
        advance(Duration.ofMinutes(3).plusSeconds(1));
        grid(-1100);
        tick();
        verifyDisabled();

        // good export again and start delay elapsed, but cooldown blocks restart
        advance(Duration.ofMinutes(2).plusSeconds(1));
        grid(600);
        tick();
        verifyDisabled();

        // once cooldown elapses, charging may resume
        advance(Duration.ofMinutes(3));
        grid(600);
        tick();
        verifySetState(6, 1);
    }

    @Test
    public void testSpikeImmediateStop() {
        startCharging("FAST", 0);

        // import well over budget+10% (7400 * 1.1 = 8140) -> immediate stop, no debounce
        grid(-9000);
        tick();
        verifyDisabled();

        // cooldown is active even though conditions are fine again
        advance(Duration.ofMinutes(2).plusSeconds(1));
        grid(0);
        tick();
        verifyDisabled();
    }

    @Test
    public void testStaleInput() {
        startCharging("PV_AND_MIN", 0);

        // no fresh reading for longer than the input timeout -> safety disable
        advance(Duration.ofMinutes(1).plusSeconds(1));
        tick();
        verifyDisabled();
    }

    @Test
    public void testFastBudgetAndPhaseSwitch() {
        startCharging("FAST", 0);

        // budget = maxGridPower + surplus = 7400W. 3-phase desired but phase debounce not met:
        // stays 1 phase, current clamped to max.
        grid(0);
        tick();
        verifySetState(16, 1);

        // sustain for the phase-switch delay -> switch to 3 phase, current from budget/(230*3)
        advance(Duration.ofMinutes(5).plusSeconds(1));
        grid(0);
        tick();
        verifySetState(7400 / (230.0 * 3), 3);
    }

    /**
     * Bring the writer into the charging state for the given mode with the given net export,
     * leaving the clock just past the start delay. Resets client invocations afterwards.
     */
    private void startCharging(String mode, int netExport) {
        setMode(mode);
        grid(netExport);
        tick();
        advance(config.startDelay().plusSeconds(1));
        grid(netExport);
        tick();
        // sanity: charging should have started
        verify(client, atLeastOnce()).setState(eq(SOCKET), anyFloat(), anyInt());
        clearInvocations(client);
    }
}
