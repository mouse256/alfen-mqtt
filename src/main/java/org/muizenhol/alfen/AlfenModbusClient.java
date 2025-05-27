package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersResponse;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import io.vertx.core.Vertx;
import org.muizenhol.homeassistant.Discovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AlfenModbusClient implements AutoCloseable{

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ModbusTcpClient client;
    private final Vertx vertx;
    private final String name;
    private final MqttPublisher mqttPublisher;
    private final boolean writeEnabled;

    public AlfenModbusClient(Vertx vertx, AlfenConfig.Device deviceConfig, boolean writeEnabled, MqttPublisher mqttPublisher) {
        this.vertx = vertx;
        var transport = NettyTcpClientTransport.create(cfg -> {
            cfg.hostname = deviceConfig.endpoint();
            cfg.port = deviceConfig.port();
        });

        client = ModbusTcpClient.create(transport);
        name = deviceConfig.name();
        this.mqttPublisher = mqttPublisher;
        this.writeEnabled = writeEnabled;
        try {
            setStates.put(client, new HashMap<>());
            client.connect();
            sendDiscovery(deviceConfig, client);
        } catch (Exception e) {
            LOG.warn("Error connecting to modbus client: {}", deviceConfig.endpoint(), e);
            return;
        }

        vertx.setPeriodic(0, Duration.ofSeconds(1).toMillis(), this::poll);
        if (writeEnabled) {
            LOG.info("Startup: write enabled");
            vertx.setPeriodic(0, Duration.ofSeconds(10).toMillis(), this::pollWrite);
        } else {
            LOG.info("Startup: write disabled");
        }
    }

    @Override
    public void close() {
        try {
            client.disconnect();
        } catch (ModbusExecutionException e) {
            LOG.debug("Error stopping modbus client", e);
        }
    }

    private record SetState(boolean enabled, float maxCurrent) {
    }

    private final Map<ModbusTcpClient, Map<Integer, SetState>> setStates = new HashMap<>();

    private void poll(long l) {
        LOG.debug("Polling...");
        vertx.executeBlocking(() -> {
            readData();
            return null;
        });
    }

    private void pollWrite(long l) {
        //ModBus has a safety that you need to keep writing.
        //If the connection drops, it will fall back to a default value.
        LOG.debug("Write loop...");
        vertx.executeBlocking(() -> {
            //writeData();
            return null;
        });
    }

    private void readData() {
        readData(ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC, true);
        readData(ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC, true).ifPresent(values -> {
            Object nrOfSockets = values.get(ModbusConst.ID_NR_OF_SOCKETS);
            if (nrOfSockets != null) {
                int nrOfSocketsInt = (int) nrOfSockets;
                LOG.debug("NrOfSockets: {}", nrOfSocketsInt);
                for (int i = 1; i <= nrOfSocketsInt; ++i) {
                    Optional<Map<Integer, Object>> socketMeasurement = readData(ModbusConst.SOCKET_MEASUREMENT, i, true);
                    Optional<Map<Integer, Object>> status = readData(ModbusConst.STATUS, i, true);
                    //evccHandler.writeEvcc(name, i, status, socketMeasurement);
                }
            } else {
                LOG.warn("Can't fetch number of sockets. Got null");
            }
        });
    }

    private Optional<Map<Integer, Object>> readData(ModbusConst.Group group, int unitId, boolean writeMqtt) {
        try {
            LOG.debug("Reading group: {}", group.startOffset());
            ReadHoldingRegistersResponse response = client.readHoldingRegisters(
                    unitId,
                    new ReadHoldingRegistersRequest(group.startOffset(), group.size())
            );
            LOG.debug("ReadHoldingRegistersResponse: {} -- {}", response.registers().length, response.getFunctionCode());
            ByteBuffer buf = ByteBuffer.wrap(response.registers());
            Map<Integer, Object> values = group.items().stream()
                    .collect(Collectors.toMap(ModbusConst.Item::start, i -> convert(i, buf, group)));
            if (writeMqtt) {
                //Having ints as keys in json makes the parsing hard on some tools/libraries.
                //So prefix with "S" from start to make them a string.
                Map<String, Object> values2 = group.items().stream()
                        .collect(Collectors.toMap(i -> "S" + i.start(), i -> convert(i, buf, group)));
                mqttPublisher.sendModbus(name, group.name(), values2, unitId);
            }
            return Optional.of(values);

        } catch (Exception e) {
            LOG.warn("Error reading data", e);
        }
        return Optional.empty();
    }

    private Object convert(ModbusConst.Item item, ByteBuffer buf, ModbusConst.Group group) {
        return switch (item.type()) {
            case STRING -> readString(buf, item.start() - group.startOffset(), item.size(), item.name());
            case FLOAT32 -> readFloat(buf, item.start() - group.startOffset(), item.name());
            case FLOAT64 -> readDouble(buf, item.start() - group.startOffset(), item.name());
            case SIGNED16 -> readShort(buf, item.start() - group.startOffset(), item.name());
            case UNSIGNED16 -> readShortUnsigned(buf, item.start() - group.startOffset(), item.name());
            //note: unsigned64 is not correct, but chances of having such a big value is slim
            case UNSIGNED32, UNSIGNED64 -> readIntUnsigned(buf, item.start() - group.startOffset(), item.name());
        };
    }

    private float readFloat(ByteBuffer buf, int i, String name) {
        float f = buf.getFloat(i * 2);
        LOG.debug("{}: {}: {}", i, name, f);
        return f;
    }

    private double readDouble(ByteBuffer buf, int i, String name) {
        double d = buf.getDouble(i * 2);
        LOG.debug("{}: {}: {}", i, name, d);
        return d;
    }

    private short readShort(ByteBuffer buf, int i, String name) {
        short s = buf.getShort(i * 2);
        LOG.debug("{}: {}: {}", i, name, s);
        return s;
    }

    private int readShortUnsigned(ByteBuffer buf, int i, String name) {
        int us = buf.getShort(i * 2) & 0x0000ffff;
        LOG.debug("{}: {}: {}", i, name, us);
        return us;
    }

    private long readIntUnsigned(ByteBuffer buf, int i, String name) {
        long ui = buf.getInt(i * 2) & 0x00000000ffffffffL;
        LOG.debug("{}: {}: {}", i, name, ui);
        return ui;
    }

    private String readString(ByteBuffer buf, int i, int length, String name) {
        //LOG.info("Decoding string from {} size {}", i * 2, length * 2);

        CharBuffer cb = StandardCharsets.UTF_8.decode(buf.slice(i * 2, length * 2));
        //it decodes the full buffer, leaving a string with a lot of null-terminates characters.
        //so loop over the buffer to find the first nullbyte, and mark that as the buffer end
        while (cb.hasRemaining()) {
            char c = cb.get();
            if (c == 0x0) {
                cb.limit(cb.position() - 1);
            }
        }
        cb.position(0);
        String str = cb.toString();
        LOG.debug("{}: {}: {}", i, name, str);
        return str;
    }


    void writeDataFloat(float value, ModbusTcpClient client, ModbusConst.Item item, int unitId) {
        writeData(s -> {
            s.putFloat(value);
            LOG.debug("Writing float item {} : {}", item.name(), value);
        }, client, item, unitId);
    }

    private void writeDataUnsigned16(int value, ModbusTcpClient client, ModbusConst.Item item, int unitId) {
        writeData(s -> {
            s.putShort((short) value);
            LOG.debug("Writing unsigned16 item {} : {}", item.name(), value);
        }, client, item, unitId);
    }

    private void writeData(Consumer<ByteBuffer> setter, ModbusTcpClient client, ModbusConst.Item item, int unitId) {
        if (!writeEnabled) {
            return;
        }
        try {
            int address = item.start();
            ByteBuffer buf = ByteBuffer.allocate(item.size() * 2);
            setter.accept(buf);
            byte[] values = buf.array();
            if (values.length != item.size() * 2) {
                LOG.warn("Buffer wrong while writing {} {}!={}", item.name(), values.length, item.size() * 2);
                return;
            }

            LOG.debug("Wrinting to {} (unit: {}, size: {})", address, unitId, item.size() * 2);
            WriteMultipleRegistersResponse response = client.writeMultipleRegisters(unitId, new WriteMultipleRegistersRequest(address, item.size(), values));
            LOG.debug("WriteMultipleRegistersResponse: {}", response.getFunctionCode());


        } catch (Exception e) {
            LOG.warn("Error writing data", e);
        }
    }


//    public void handleWrite(String chargerName, int socket, String key, String payload) {
//        if (!writeEnabled) {
//            return;
//        }
//
//        ModbusTcpClient client = clients.get(chargerName);
//        if (client == null) {
//            LOG.warn("Unknown EVCC client: \"{}\" ({})", chargerName, clients.values());
//            return;
//        }
//
//        Map<Integer, SetState> setStateSockets = setStates.get(client);
//        if (setStateSockets == null) {
//            LOG.warn("Unknown EVCC set state: \"{}\"", chargerName);
//            return;
//        }
//        SetState setState = setStateSockets.getOrDefault(socket, new SetState(false, 0));
//        switch (key) {
//            case "enable":
//                boolean enable = Boolean.parseBoolean(payload);
//                LOG.info("Enable request for {} ({}): {}", chargerName, socket, enable);
//                setStateSockets.put(socket, new SetState(enable, setState.maxCurrent));
//                break;
//            case "maxCurrent":
//                try {
//                    float valueF = Float.parseFloat(payload);
//                    LOG.info("MaxCurrent request for {} ({}): {}", chargerName, socket, valueF);
//                    setStateSockets.put(socket, new SetState(setState.enabled, valueF));
//                } catch (NumberFormatException e) {
//                    LOG.warn("Error parsing float: {}", payload);
//                }
//                break;
//            default:
//                LOG.warn("Unknown EVCC key: {}", key);
//        }
//        writeData();
//    }
//
//    private void writeData() {
//        setStates.forEach((client, setStateSockets) -> {
//            setStateSockets.forEach((socket, state) -> {
//                LOG.debug("Writing state for socket {} ({})", socket, state);
//                if (state.enabled) {
//                    float maxCurrent = state.maxCurrent;
//                    int phases = 1;
//                    if (state.maxCurrent >= 18) {
//                        maxCurrent = state.maxCurrent / 3;
//                        phases = 3;
//                    }
//                    writeDataFloat(maxCurrent, client, ModbusConst.ITEM_MAX_CURRENT, socket);
//                    //writeDataUnsigned16(phases, client, ModbusConst.ITEM_NUM_PHASES, socket);
//                } else {
//                    writeDataFloat(0, client, ModbusConst.ITEM_MAX_CURRENT, socket);
//                }
//            });
//        });
//    }

    public void sendDiscovery(AlfenConfig.Device deviceConfig, ModbusTcpClient client) {
        LOG.info("Generating discovery for {}", deviceConfig.name());


        int nrOfSockets = readData(ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC, false)
                .flatMap(values -> Optional.ofNullable(values.get(ModbusConst.ID_NR_OF_SOCKETS)))
                .map(x -> (int) x)
                .orElse(-1);
        if (nrOfSockets == -1) {
            LOG.warn("Can't fetch number of sockets, can't publish discovery info for {}", deviceConfig.name());
            return;
        }

        String serial = (String) readData(ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC, false)
                .flatMap(values -> Optional.ofNullable(values.get(ModbusConst.ID_STATION_SERIAL_NUMBER)))
                .orElse(null);
        if (serial == null) {
            LOG.warn("Can't fetch serial number, can't publish discovery info for {}", deviceConfig.name());
            return;
        }

        for (int s = 1; s <= nrOfSockets; s++) {

            Map<String, Discovery.Component> components = ModbusConst.SOCKET_MEASUREMENT.items().stream()
                    .filter(i -> i.discoveryInfo() != null)
                    .map(i -> new Discovery.Component(
                            i.name(),
                            "socket_measurement_" + i.start(),
                            Discovery.Component.Platform.SENSOR,
                            i.discoveryInfo().deviceClass(),
                            i.discoveryInfo().stateClass(),
                            i.discoveryInfo().unit(),
                            i.discoveryInfo().precision(),
                            "{{ value_json.S" + i.start() + " }}"
                    )).collect(Collectors.toMap(Discovery.Component::uniqueId, i -> i));
            String uuid = serial + "-" + s;
            Discovery discovery = new Discovery(
                    new Discovery.Device(
                            uuid,
                            "mouse256",
                            "alfen",
                            "alfen-mqtt"
                    )
                    , new Discovery.Origin(
                    "alfen-mqtt"
            ),
                    "alfen/modbus/state/" + deviceConfig.name() + "/" + s + "/socket_measurement",
                    components
            );
            mqttPublisher.sendDiscovery(discovery);
        }
    }
}
