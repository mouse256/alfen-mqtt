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

public class AlfenModbusClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ModbusTcpClient client;
    private final Vertx vertx;
    private final String name;
    private final MqttPublisher mqttPublisher;
    private final boolean writeEnabled;
    private final MqttHandler mqttListener;
    private final Map<Integer, AlfenModbusWriter> writers = new HashMap<>();

    /**
     * States to set/write. Indexed per socket.
     */
    private final Map<Integer, SetState> setStates = new HashMap<>();

    /**
     * Last state read. Indexed per socket
     */
    private final Map<Integer, Map<Integer, Object>> socketStatus = new HashMap<>();

    /**
     * Last socket measurement read. Indexed per socket
     */
    private final Map<Integer, Map<Integer, Object>> socketMeasurement = new HashMap<>();


    private record SetState(boolean enabled, float maxCurrent, int numPhases) {
    }

    AlfenModbusClient(Vertx vertx, String name, ModbusTcpClient client, boolean writeEnabled, MqttPublisher mqttPublisher, MqttHandler mqttListener) {
        this.vertx = vertx;
        this.client = client;
        this.name = name;
        this.mqttPublisher = mqttPublisher;
        this.mqttListener = mqttListener;
        this.writeEnabled = writeEnabled;
    }

    public AlfenModbusClient(Vertx vertx, AlfenConfig.Device deviceConfig, boolean writeEnabled, MqttPublisher mqttPublisher, MqttHandler mqttListener) {
        this(vertx, deviceConfig.name(), createClient(deviceConfig), writeEnabled, mqttPublisher, mqttListener);
        start(true);
    }

    private static ModbusTcpClient createClient(AlfenConfig.Device deviceConfig) {
        var transport = NettyTcpClientTransport.create(cfg -> {
            cfg.hostname = deviceConfig.endpoint();
            cfg.port = deviceConfig.port();
        });

        return ModbusTcpClient.create(transport);
    }

    void start(boolean pollEnabled) {
        try {
            client.connect();
            sendDiscovery();
        } catch (Exception e) {
            LOG.warn("Error connecting to modbus client: {}", name, e);
            return;
        }

        if (pollEnabled) {
            vertx.setPeriodic(0, Duration.ofSeconds(1).toMillis(), this::poll);
//            if (writeEnabled) {
//                LOG.info("Startup: write enabled");
//                vertx.setPeriodic(0, Duration.ofSeconds(10).toMillis(), this::pollWrite);
//            } else {
//                LOG.info("Startup: write disabled");
//            }
        }
    }

    @Override
    public void close() {
        try {
            client.disconnect();
        } catch (ModbusExecutionException e) {
            LOG.debug("Error stopping modbus client", e);
        }
        writers.values().forEach(AlfenModbusWriter::close);
        writers.clear();
    }

    public String getName() {
        return name;
    }

    private void poll(long l) {
        LOG.debug("Polling...");
        vertx.executeBlocking(() -> {
            pollRead();
            return null;
        });
    }

    void pollRead() {
        readData();
    }

//    private void pollWrite(long l) {
//        //ModBus has a safety that you need to keep writing.
//        //If the connection drops, it will fall back to a default value.
//        LOG.debug("Write loop...");
//        vertx.executeBlocking(() -> {
//            //writeData();
//            return null;
//        });
//    }

    private synchronized void readData() {
        readData(ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC, true);
        readData(ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC, true).ifPresent(values -> {
            Object nrOfSockets = values.get(ModbusConst.ID_NR_OF_SOCKETS);
            if (nrOfSockets != null) {
                int nrOfSocketsInt = (int) nrOfSockets;
                LOG.debug("NrOfSockets: {}", nrOfSocketsInt);
                for (int i = 1; i <= nrOfSocketsInt; ++i) {
                    final int socket = i;
                    readData(ModbusConst.SOCKET_MEASUREMENT, i, true)
                            .ifPresent(s -> socketMeasurement.put(socket, s));
                    readData(ModbusConst.STATUS, i, true)
                            .ifPresent(s -> socketStatus.put(socket, s));
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
            mqttPublisher.sendModbus(name, group.name(), values, unitId);

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

    public void sendDiscovery() {
        LOG.info("Generating discovery for {}", name);


        int nrOfSockets = readData(ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC, false)
                .flatMap(values -> Optional.ofNullable(values.get(ModbusConst.ID_NR_OF_SOCKETS)))
                .map(x -> (int) x)
                .orElse(-1);
        if (nrOfSockets == -1) {
            LOG.warn("Can't fetch number of sockets, can't publish discovery info for {}", name);
            return;
        }

        String serial = (String) readData(ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC, false)
                .flatMap(values -> Optional.ofNullable(values.get(ModbusConst.ID_STATION_SERIAL_NUMBER)))
                .orElse(null);
        if (serial == null) {
            LOG.warn("Can't fetch serial number, can't publish discovery info for {}", name);
            return;
        }

        for (int s = 1; s <= nrOfSockets; s++) {
            writers.put(s, new AlfenModbusWriter(vertx, this, name, s, mqttListener));

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
                    "alfen/modbus/state/" + name + "/" + s + "/socket_measurement",
                    components
            );
            mqttPublisher.sendDiscovery(discovery);
        }
    }

    private void pollWrite() {

    }


    public void disable(int socket) {
        LOG.info("Set socket {} to disabled", socket);
        setStates.put(socket, new SetState(false, 0, 1));

        writeData();
    }

    public void setState(int socket, float maxCurrent, int numPhases) {
        LOG.info("Set socket {} to enabled, maxCurrent: {}, numPhases: {}", socket, maxCurrent, numPhases);
        setStates.put(socket, new SetState(true, maxCurrent, numPhases));

        writeData();
    }

    public synchronized Optional<Integer> getSocketRealPowerSum(int socket) {
        Map<Integer, Object> measure = socketMeasurement.get(socket);
        if (measure == null) {
            return Optional.empty();
        }
        return Optional.of(Math.round((Float) measure.get(ModbusConst.ITEM_REAL_POWER_SUM.start())));
    }

    private synchronized void writeData() {
        if (!writeEnabled) {
            return;
        }
        pollRead(); //update state
        setStates.forEach((socket, state) -> {
            LOG.debug("Writing state for socket {} ({})", socket, state);
            if (state.enabled) {
                Map<Integer, Object> currentStatus = socketStatus.get(socket);
                if (currentStatus == null) {
                    LOG.warn("Current status for socket {} unknown", socket);
                    return;
                }
                writeDataFloat(state.maxCurrent, client, ModbusConst.ITEM_MAX_CURRENT, socket);
                int numPhases = (int) currentStatus.get(ModbusConst.ITEM_NUM_PHASES.start());
                if (numPhases != state.numPhases) {
                    LOG.info("Changing number of phases to charge from {} to {}", numPhases, state.numPhases);
                    writeDataUnsigned16(state.numPhases, client, ModbusConst.ITEM_NUM_PHASES, socket);
                }

            } else {
                writeDataFloat(0, client, ModbusConst.ITEM_MAX_CURRENT, socket);
            }
        });
    }
}
