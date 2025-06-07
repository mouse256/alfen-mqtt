package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.muizenhol.homeassistant.Discovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
public class AlfenModbusClientTest {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @InjectMock
    MqttPublisher mqttPublisher;

    @InjectMock
    MqttListener mqttListener;

    @Inject
    Vertx vertx;

    private ModbusTcpClient mockClient;

    private AlfenModbusClient alfenModbusClient;
    private static final float testFloatValue = 78.45f;

    @BeforeEach
    void setup() {
        mockClient = Mockito.mock(ModbusTcpClient.class);
        alfenModbusClient = new AlfenModbusClient(vertx, "test1", mockClient, true, mqttPublisher, mqttListener);
    }

    private void prepare() throws Exception {
        Mockito.doAnswer(invocationOnMock -> {
            int unitId = invocationOnMock.getArgument(0, Integer.class);
            ReadHoldingRegistersRequest req = invocationOnMock.getArgument(1
                    , ReadHoldingRegistersRequest.class);
            LOG.info("Received ReadHoldingRegistersRequest unit: {} -- addr: {} -- function: {}", unitId, req.address(), req.getFunctionCode());
            return new ReadHoldingRegistersResponse(makeRegisters(unitId, req));
        }).when(mockClient).readHoldingRegisters(Mockito.anyInt(), Mockito.any());
    }


    @Test
    void testDiscovery() throws Exception {
        prepare();

        //exec
        alfenModbusClient.start(false);

        //verify
        ArgumentCaptor<Discovery> argumentCaptor = ArgumentCaptor.forClass(Discovery.class);
        verify(mqttPublisher).sendDiscovery(argumentCaptor.capture());

        Discovery capturedArgument = argumentCaptor.getValue();
        assertThat(capturedArgument.stateTopic(), Matchers.equalTo("alfen/modbus/state/test1/1/socket_measurement"));
        assertThat(capturedArgument.components().size(), Matchers.equalTo(9));
    }

    @Test
    void testRead() throws Exception {
        prepare();

        //exec
        alfenModbusClient.pollRead();

        //verify
        ArgumentCaptor<Map<Integer, Object>> argumentCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mqttPublisher, times(4)).sendModbus(any(), any(), any(), anyInt());
        verify(mqttPublisher).sendModbus(ArgumentMatchers.eq("test1"),
                ArgumentMatchers.eq(ModbusConst.PRODUCT_IDENTIFICATION.name()),
                argumentCaptor.capture(),
                ArgumentMatchers.eq(ModbusConst.ADDR_GENERIC));
        assertThat(argumentCaptor.getValue().size(), Matchers.equalTo(ModbusConst.PRODUCT_IDENTIFICATION.items().size()));

        verify(mqttPublisher).sendModbus(ArgumentMatchers.eq("test1"),
                ArgumentMatchers.eq(ModbusConst.STATUS.name()),
                argumentCaptor.capture(),
                ArgumentMatchers.eq(1));
        assertThat(argumentCaptor.getValue().size(), Matchers.equalTo(ModbusConst.STATUS.items().size()));

        verify(mqttPublisher).sendModbus(ArgumentMatchers.eq("test1"),
                ArgumentMatchers.eq(ModbusConst.STATION_STATUS.name()),
                argumentCaptor.capture(),
                ArgumentMatchers.eq(ModbusConst.ADDR_GENERIC));
        assertThat(argumentCaptor.getValue().size(), Matchers.equalTo(ModbusConst.STATION_STATUS.items().size()));

        verify(mqttPublisher).sendModbus(ArgumentMatchers.eq("test1"),
                ArgumentMatchers.eq(ModbusConst.SOCKET_MEASUREMENT.name()),
                argumentCaptor.capture(),
                ArgumentMatchers.eq(1));
        Map<?, Object> socketMeasure = argumentCaptor.getValue();
        assertThat(socketMeasure.size(), Matchers.equalTo(ModbusConst.SOCKET_MEASUREMENT.items().size()));
        assertThat(socketMeasure.get(344),  Matchers.equalTo(testFloatValue)); //real power sum
    }

    private byte[] makeRegisters(int unitId, ReadHoldingRegistersRequest req) {

        ModbusConst.Group group = Arrays.stream(ModbusConst.StartOffset.values())
                .filter(x -> x.offset == req.address())
                .map(so -> switch (so) {
                    case PRODUCT_IDENTIFICATION -> ModbusConst.PRODUCT_IDENTIFICATION;
                    case SOCKET_MEASUREMENT -> ModbusConst.SOCKET_MEASUREMENT;
                    case STATION_STATUS -> ModbusConst.STATION_STATUS;
                    case SOCKET_STATUS -> ModbusConst.STATUS;
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No start offset found for request: " + req.address()));

        LOG.debug("Generating mock response for {} (size {})", group.name(), group.size());
        ByteBuffer buf = ByteBuffer.allocate(group.size() * 2);
        group
                .items()
                .stream()
                .map(this::convert)
                .forEach(b -> buf.put(b.rewind()));
        return buf.array();
    }

    private ByteBuffer convert(ModbusConst.Item i) {
        LOG.debug("Allocating {} -- {} -- {}", i.name(), i.type(), i.size());
        ByteBuffer buf = ByteBuffer.allocate(i.size() * 2);
        switch (i.start()) {
            case (ModbusConst.ID_NR_OF_SOCKETS) -> buf.putShort((short) 1);
            case (344) -> buf.putFloat(testFloatValue); //real power sum
            default -> {
                switch (i.type()) {
                    case STRING -> buf.put("x".getBytes(StandardCharsets.UTF_8));
                    case SIGNED16 -> buf.putShort((short) 0x01);
                    case UNSIGNED16 -> buf.putShort((short) 0x02);
                    case UNSIGNED32 -> buf.putInt(0x03);
                    case UNSIGNED64 -> buf.putLong(0x04L);
                    case FLOAT32 -> buf.putFloat(5.1f);
                    case FLOAT64 -> buf.putDouble(6.1f);
                }
            }
        }
        return buf;
    }


}

