package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersResponse;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.mqtt.ReceivingMqttMessageMetadata;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.muizenhol.alfen.data.Evcc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class AlfenModbus {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Pattern PATTERN_EVCC = Pattern.compile("alfen/evcc/set/([^/]+)/(\\d+)/(.*)");

    @Inject
    AlfenConfig alfenConfig;

    @Inject
    MqttPublisher mqttPublisher;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "modbus.write_enabled", defaultValue = "false")
    boolean writeEnabled;

    private final Map<String, ModbusTcpClient> clients = new HashMap<>();

    private record SetState(boolean enabled, float maxCurrent) {
    }

    private final Map<ModbusTcpClient, Map<Integer, SetState>> setStates = new HashMap<>();

    public void onStart(@Observes StartupEvent startupEvent) {
        clients.clear();
        List<AlfenConfig.Device> deviceConfigs = alfenConfig.devices().stream()
                .filter(d -> d.type() == AlfenConfig.DeviceType.MODBUS)
                .toList();
        if (deviceConfigs.isEmpty()) {
            LOG.info("No Alfen MODBUS devices configured");
            return;
        }
        LOG.info("Startup: endpoints: {}", deviceConfigs.stream()
                .map(e -> e.name() + ": " + e.endpoint())
                .collect(Collectors.toList()));

        deviceConfigs.forEach(deviceConfig -> {
            var transport = NettyTcpClientTransport.create(cfg -> {
                cfg.hostname = deviceConfig.endpoint();
                cfg.port = 502;
            });

            try {
                ModbusTcpClient client = ModbusTcpClient.create(transport);
                setStates.put(client, new HashMap<>());
                client.connect();
                clients.put(deviceConfig.name(), client);

            } catch (Exception e) {
                LOG.warn("Error connecting to modbus client: {}", deviceConfig.endpoint(), e);
            }
        });

        vertx.setPeriodic(0, Duration.ofSeconds(1).toMillis(), this::poll);
        if (writeEnabled) {
            LOG.info("Startup: write enabled");
            vertx.setPeriodic(0, Duration.ofSeconds(10).toMillis(), this::pollWrite);
        } else {
            LOG.info("Startup: write disabled");
        }
    }

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        clients.forEach((name, client) -> {
            try {
                client.disconnect();
            } catch (ModbusExecutionException e) {
                LOG.debug("Disconnect failed", e);
            }
        });
        clients.clear();
    }

    private void poll(long l) {
        LOG.debug("Polling...");
        vertx.executeBlocking(() -> {
            clients.forEach(this::readData);
            return null;
        });
    }

    private void pollWrite(long l) {
        //ModBus has a safety that you need to keep writing.
        //If the connection drops, it will fall back to a default value.
        LOG.debug("Write loop...");
        vertx.executeBlocking(() -> {
            writeData();
            return null;
        });
    }

    private void readData(String name, ModbusTcpClient client) {
        readData(name, client, ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC);
        readData(name, client, ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC).ifPresent(values -> {
            Object nrOfSockets = values.get(ModbusConst.ID_NR_OF_SOCKETS);
            if (nrOfSockets != null) {
                int nrOfSocketsInt = (int) nrOfSockets;
                LOG.debug("NrOfSockets: {}", nrOfSocketsInt);
                for (int i = 1; i <= nrOfSocketsInt; ++i) {
                    Optional<Map<Integer, Object>> socketMeasurement = readData(name, client, ModbusConst.SOCKET_MEASUREMENT, i);
                    Optional<Map<Integer, Object>> status = readData(name, client, ModbusConst.STATUS, i);
                    writeEvcc(name, i, status, socketMeasurement);
                }
            } else {
                LOG.warn("Can't fetch number of sockets. Got null");
            }
        });
    }

    private Optional<Map<Integer, Object>> readData(String name, ModbusTcpClient client, ModbusConst.Group group, int unitId) {
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
            //Having ints as keys in json makes the parsing hard on some tools/libraries.
            //So prefix with "S" from start to make them a string.
            Map<String, Object> values2 = group.items().stream()
                    .collect(Collectors.toMap(i -> "S" + i.start(), i -> convert(i, buf, group)));
            mqttPublisher.sendModbus(name, group.name(), values2, unitId);
            return Optional.of(values);

        } catch (Exception e) {
            LOG.warn("Error reading data", e);
        }
        return Optional.empty();
    }

    private void writeEvcc(String name, int addr, Optional<Map<Integer, Object>> statusOpt, Optional<Map<Integer, Object>> socketMeasurementOpt) {
        try {
            Map<Integer, Object> status = statusOpt.orElseThrow(() -> new IllegalStateException("Status not present, can't send evcc"));
            Map<Integer, Object> socketMeasurement = socketMeasurementOpt.orElseThrow(() -> new IllegalStateException("SocketMeasurement not present, can't send evcc"));

            Evcc.Charger evccStatus = new Evcc.Charger(
                    Optional.ofNullable(status.get(1200))
                            .map(x -> ((Integer) x) == 1)
                            .orElseThrow(() -> new IllegalStateException("Can't find field 1200")),
                    Optional.ofNullable(status.get(1201))
                            .map(x -> switch ((String) x) {
                                        case "A" -> "A";
                                        case "B1", "B2", "C1", "D1" -> "B";
                                        case "C2", "D2" -> "C";
                                        case "E", "F" -> "E";
                                        default -> throw new IllegalStateException("Unexpected mode 3 state: " + x);
                                    }
                            )
                            .orElseThrow(() -> new IllegalStateException("Can't find field 1201")),
                    Optional.ofNullable(socketMeasurement.get(344))
                            .map(x -> (Float) x)
                            .orElseThrow(() -> new IllegalStateException("Can't find field 344"))

            );
            mqttPublisher.sendModbusEvcc(name, addr, evccStatus);
        } catch (Exception e) {
            LOG.warn("Error extracting evcc data", e);
        }
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

    @Incoming("evcc")
    public CompletionStage<Void> handleEvcc(Message<String> evcc) {
        StreamSupport.stream(evcc.getMetadata().spliterator(), false)
                .filter(x -> x instanceof ReceivingMqttMessageMetadata)
                .map(x -> (ReceivingMqttMessageMetadata) x)
                .findFirst()
                .ifPresentOrElse(meta -> handleEvccNoReturn(evcc, meta), () -> {
                    LOG.warn("Can't find MQTT message metadata");
                });

        return evcc.ack();
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

    private void handleEvccNoReturn(Message<String> evcc, ReceivingMqttMessageMetadata meta) {
        if (!writeEnabled) {
            return;
        }
        Matcher m = PATTERN_EVCC.matcher(meta.getTopic());
        if (!m.matches()) {
            LOG.warn("Unknown EVCC topic: {}", meta.getTopic());
            return;
        }
        String chargerName = m.group(1);
        int socket;
        try {
            socket = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse value as int on topic {}: {}", meta.getTopic(), m.group(2));
            return;
        }
        String key = m.group(3);
        LOG.debug("Incoming evcc message for {} ({}): {}", chargerName, socket, key);

        ModbusTcpClient client = clients.get(chargerName);
        if (client == null) {
            LOG.warn("Unknown EVCC client: \"{}\" ({})", chargerName, clients.values());
            return;
        }

        Map<Integer, SetState> setStateSockets = setStates.get(client);
        if (setStateSockets == null) {
            LOG.warn("Unknown EVCC set state: \"{}\"", chargerName);
            return;
        }
        SetState setState = setStateSockets.getOrDefault(socket, new SetState(false, 0));
        switch (key) {
            case "enable":
                boolean enable = Boolean.parseBoolean(evcc.getPayload());
                LOG.info("Enable request for {} ({}): {}", chargerName, socket, enable);
                setStateSockets.put(socket, new SetState(enable, setState.maxCurrent));
                break;
            case "maxCurrent":
                try {
                    float valueF = Float.parseFloat(evcc.getPayload());
                    LOG.info("MaxCurrent request for {} ({}): {}", chargerName, socket, valueF);
                    setStateSockets.put(socket, new SetState(setState.enabled, valueF));
                } catch (NumberFormatException e) {
                    LOG.warn("Error parsing float: {}", evcc.getMetadata());
                }
                break;
            default:
                LOG.warn("Unknown EVCC key: {}", key);
        }
        writeData();
    }

    private void writeData() {
        setStates.forEach((client, setStateSockets) -> {
            setStateSockets.forEach((socket, state) -> {
                LOG.debug("Writing state for socket {} ({})", socket, state);
                if (state.enabled) {
                    float maxCurrent = state.maxCurrent;
                    int phases = 1;
                    if (state.maxCurrent >= 18) {
                        maxCurrent = state.maxCurrent / 3;
                        phases = 3;
                    }
                    writeDataFloat(maxCurrent, client, ModbusConst.ITEM_MAX_CURRENT, socket);
                    //writeDataUnsigned16(phases, client, ModbusConst.ITEM_NUM_PHASES, socket);
                } else {
                    writeDataFloat(0, client, ModbusConst.ITEM_MAX_CURRENT, socket);
                }
            });
        });
    }
}
