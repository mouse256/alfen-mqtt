package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class AlfenModbus2Test {

    @InjectMock
    AlfenConfig alfenConfig;

    @InjectMock
    MqttPublisher mqttPublisher;

    @InjectMock
    Vertx vertx;

    @Inject
    AlfenModbus alfenModbus;

    private ModbusTcpClient mockClient;

    @BeforeEach
    void setup() {
        mockClient = Mockito.mock(ModbusTcpClient.class);
        alfenModbus.clients.clear();
    }

//    @Test
//    void testConnectionSuccess() {
//        // Setup
//        Mockito.when(alfenConfig.devices()).thenReturn(Set.of(
//                new AlfenConfig.Device() {
//                    public String endpoint() { return "test-endpoint"; }
//                    public String name() { return "test-device"; }
//                    public AlfenConfig.DeviceType type() { return AlfenConfig.DeviceType.MODBUS; }
//                }
//        ));
//
//        // Execute
//        alfenModbus.onStart(null);
//
//        // Verify
//        assertEquals(1, alfenModbus.clients.size());
//        Mockito.verify(mockClient).connect();
//    }
//
//    @Test
//    void testConnectionFailure() {
//        // Setup
//        Mockito.when(alfenConfig.devices()).thenReturn(Set.of(
//                new AlfenConfig.Device() {
//                    public String endpoint() { return "invalid-endpoint"; }
//                    public String name() { return "test-device"; }
//                    public AlfenConfig.DeviceType type() { return AlfenConfig.DeviceType.MODBUS; }
//                }
//        ));
//        Mockito.doThrow(new RuntimeException("Connection failed"))
//                .when(mockClient).connect();
//
//        // Execute
//        alfenModbus.onStart(null);
//
//        // Verify
//        assertTrue(alfenModbus.clients.isEmpty());
//    }
//
//    @Test
//    void testReadHoldingRegisters() {
//        // Setup
//        ReadHoldingRegistersResponse mockResponse = Mockito.mock(ReadHoldingRegistersResponse.class);
//        Mockito.when(mockResponse.registers()).thenReturn(new short[]{1, 2, 3});
//        Mockito.when(mockClient.readHoldingRegisters(200, Mockito.any())).thenReturn(mockResponse);
//
//        // Execute
//        Optional<Map<Integer, Object>> result = alfenModbus.readData("test-device", mockClient, ModbusConst.PRODUCT_IDENTIFICATION, 200);
//
//        // Verify
//        assertTrue(result.isPresent());
//        assertEquals(79, result.get().size());
//    }
//
//    @Test
//    void testReadErrorHandling() {
//        // Setup
//        Mockito.when(mockClient.readHoldingRegisters(200, Mockito.any())).thenThrow(new RuntimeException());
//
//        // Execute
//        Optional<Map<Integer, Object>> result = alfenModbus.readData("test-device", mockClient, ModbusConst.PRODUCT_IDENTIFICATION, 200);
//
//        // Verify
//        assertFalse(result.isPresent());
//    }
//
//    @Test
//    void testHandleEvccEnable() {
//        // Setup
//        Message<String> mockMessage = Mockito.mock(Message.class);
//        Mockito.when(mockMessage.getPayload()).thenReturn("true");
//        Mockito.when(mockMessage.getMetadata()).thenReturn(List.of(new ReceivingMqttMessageMetadata("test-topic")));
//
//        // Execute
//        alfenModbus.handleEvcc(mockMessage);
//
//        // Verify
//        Mockito.verify(mockClient).writeMultipleRegisters(Mockito.anyInt(), Mockito.any());
//    }
//
//    @Test
//    void testHandleEvccMaxCurrent() {
//        // Setup
//        Message<String> mockMessage = Mockito.mock(Message.class);
//        Mockito.when(mockMessage.getPayload()).thenReturn("10.5");
//        Mockito.when(mockMessage.getMetadata()).thenReturn(List.of(new ReceivingMqttMessageMetadata("test-topic")));
//
//        // Execute
//        alfenModbus.handleEvcc(mockMessage);
//
//        // Verify
//        Mockito.verify(mockClient).writeMultipleRegisters(Mockito.anyInt(), Mockito.any());
//    }
//
//
//    @Test
//    void testSendDiscovery() {
//        // Setup
//        Mockito.when(mockClient.readHoldingRegisters(200, Mockito.any())).thenReturn(
//                new ReadHoldingRegistersResponse(new short[]{1, 2, 3})
//        );
//        Mockito.when(mockClient.readHoldingRegisters(1100, Mockito.any())).thenReturn(
//                new ReadHoldingRegistersResponse(new short[]{4, 5, 6})
//        );
//
//        // Execute
//        alfenModbus.sendDiscovery(new AlfenConfig.Device() {
//            public String endpoint() { return "test-endpoint"; }
//            public String name() { return "test-device"; }
//            public AlfenConfig.DeviceType type() { return AlfenConfig.DeviceType.MODBUS; }
//        }, mockClient);
//
//        // Verify
//        Mockito.verify(mqttPublisher).sendDiscovery(Mockito.any());
//    }


}

