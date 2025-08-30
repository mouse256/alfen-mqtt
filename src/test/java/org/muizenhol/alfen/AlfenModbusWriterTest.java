package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
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
    Listener listenerSolar;
    AlfenModbusWriter writer;
    private static final String CHARGER_NAME = "dummy";
    private static final int SOCKET = 25;
    private Integer chargerPowerConsumed;
    private int disableCount = 0;
    private int setStateCount = 0;

    @BeforeEach
    public void beforeEach() {
        disableCount = 0;
        setStateCount = 0;
        client = Mockito.mock(AlfenModbusClient.class);
        mqttHandler = Mockito.mock(MqttHandler.class);
        vertx = Mockito.mock(Vertx.class);
        chargerPowerConsumed = null;
        WriterConfig writerConfig = new WriterConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Duration interval() {
                return Duration.ofMillis(100);
            }
        };

        writer = new AlfenModbusWriter(null, client, CHARGER_NAME, SOCKET, mqttHandler, writerConfig);

        listenerSet = registerMock(AlfenModbusWriter.TOPIC_SET);
        listenerPowerConsumed = registerMock(AlfenModbusWriter.TOPIC_POWER_CONSUMED);
        listenerPowerProduced = registerMock(AlfenModbusWriter.TOPIC_POWER_PRODUCED);
        listenerSolar = registerMock(AlfenModbusWriter.TOPIC_SOLAR);

        doAnswer(invocation -> Optional.ofNullable(chargerPowerConsumed)).when(client).getSocketRealPowerSum(SOCKET);
    }

    private Listener registerMock(String pattern) {
        ArgumentCaptor<MqttHandler.Listener> argumentCaptor = ArgumentCaptor.forClass(MqttHandler.Listener.class);
        ArgumentCaptor<Pattern> argumentCaptor2 = ArgumentCaptor.forClass(Pattern.class);
        verify(mqttHandler).register(argumentCaptor2.capture(), ArgumentMatchers.eq(pattern), argumentCaptor.capture());
        return new Listener(argumentCaptor.getValue(), argumentCaptor2.getValue());
    }

    private void write(String payload) {
        String topic = "alfen/set/" + CHARGER_NAME + "/" + SOCKET + "/mode";
        Matcher m = listenerSet.p.matcher(topic);
        if (!m.matches()) {
            throw new IllegalStateException("no match");
        }
        listenerSet.l.handleMessage(topic, m, payload);
    }

    private void writeEnergy(int powerProduced, int powerConsumed) {
        Matcher m1 = listenerPowerProduced.p.matcher(AlfenModbusWriter.TOPIC_POWER_PRODUCED);
        if (!m1.matches()) {
            throw new IllegalStateException("no match");
        }
        listenerPowerProduced.l.handleMessage(AlfenModbusWriter.TOPIC_POWER_PRODUCED, m1, Double.toString(powerProduced / 1000.));

        Matcher m2 = listenerPowerConsumed.p.matcher(AlfenModbusWriter.TOPIC_POWER_CONSUMED);
        if (!m2.matches()) {
            throw new IllegalStateException("no match");
        }
        listenerPowerConsumed.l.handleMessage(AlfenModbusWriter.TOPIC_POWER_CONSUMED, m2, Double.toString(powerConsumed / 1000.));
    }

    @Test
    public void test1() {
        // charger = off
        chargerPowerConsumed = 0;
        write("PV_AND_MIN");
        writer.update(1L);
        verify(client).setState(ArgumentMatchers.eq(SOCKET), ArgumentMatchers.eq(6f), ArgumentMatchers.eq(1));

        write("OFF");
        writer.update(1L);
        verify(client).setState(ArgumentMatchers.eq(SOCKET), ArgumentMatchers.eq(6f), ArgumentMatchers.eq(1));
        verify(client).disable(ArgumentMatchers.eq(SOCKET));
    }


    private void checkDisabled() {
        disableCount++;
        verify(client, times(disableCount)).disable(ArgumentMatchers.eq(SOCKET));
    }

    private void checkSetState(float test) {
        setStateCount++;
        ArgumentCaptor<Float> ac = ArgumentCaptor.forClass(Float.class);
        verify(client, times(setStateCount)).setState(ArgumentMatchers.eq(SOCKET), ac.capture(), ArgumentMatchers.eq(1));
        assertThat(ac.getValue(), equalTo(test));
    }

    @Test
    public void testPvOnly() {
        // charger = off
        chargerPowerConsumed = 0;
        write("PV_ONLY");
        writer.update(1L);
        checkDisabled();

        //still disabled
        writer.update(1L);
        checkDisabled();

        //power available
        writeEnergy(500, 0);
        writer.update(1L);
        checkSetState(6f);

        writer.update(1L);
        checkSetState(6f);

    }
}
