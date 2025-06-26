package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class AlfenModbusWriterTest {

    private record Listener(MqttHandler.Listener l, Pattern p){}

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

    @BeforeEach
    public void beforeEach() {
        client = Mockito.mock(AlfenModbusClient.class);
        mqttHandler = Mockito.mock(MqttHandler.class);
        vertx = Mockito.mock(Vertx.class);
        chargerPowerConsumed = null;

        writer = new AlfenModbusWriter(null, client, CHARGER_NAME, SOCKET, mqttHandler, false);

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

    private void write(Listener listener, String topic, String payload) {
        Matcher m = listener.p.matcher(topic);
        if (!m.matches()) {
            throw new IllegalStateException("no match");
        }
        listenerSet.l.handleMessage("alfen/set/" + CHARGER_NAME + "/" + SOCKET + "/mode", m, payload);
    }

    @Test
    public void test1() {
        // charger = off
        chargerPowerConsumed = 0;
        write(listenerSet, "alfen/set/" + CHARGER_NAME + "/" + SOCKET + "/mode", "PV_ONLY");
        writer.update(1L);
        verify(client).setState(ArgumentMatchers.eq(SOCKET), ArgumentMatchers.eq(6f), ArgumentMatchers.eq(1));

        write(listenerSet, "alfen/set/" + CHARGER_NAME + "/" + SOCKET + "/mode", "OFF");
        writer.update(1L);
        verify(client).setState(ArgumentMatchers.eq(SOCKET), ArgumentMatchers.eq(6f), ArgumentMatchers.eq(1));
        verify(client).disable(ArgumentMatchers.eq(SOCKET));

    }
}
