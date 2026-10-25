package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersResponse;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import io.quarkus.runtime.Quarkus;
import io.vertx.core.Vertx;
import org.muizenhol.homeassistant.discovery.Discovery;
import org.muizenhol.homeassistant.discovery.DeviceClass;
import org.muizenhol.homeassistant.discovery.StateClass;
import org.muizenhol.homeassistant.discovery.component.BinarySensor;
import org.muizenhol.homeassistant.discovery.component.Component;
import org.muizenhol.homeassistant.discovery.component.Select;
import org.muizenhol.homeassistant.discovery.component.Sensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AlfenModbusClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ModbusTcpClient client;
    private final Vertx vertx;
    private final String name;
    private final MqttPublisher mqttPublisher;
    private final WriterConfig writerConfig;
    private final SocConfig socConfig;
    private final MqttHandler mqttListener;
    private final Map<Integer, AlfenModbusWriter> writers = new HashMap<>();
    private final Map<Integer, SocEstimator> socEstimators = new HashMap<>();
    /**
     * All modbus I/O (polling and writing) runs on this single thread, so it never blocks the vert.x
     * event loop and reads/writes are naturally serialized.
     */
    private final ExecutorService modbusExecutor;
    private long pollTimerId = -1;
    private long readCount = 0;
    private int errorCount = 0;

    /**
     * Poll-loop health, used by {@link HealthResource} to detect a wedged worker (deadlock/hang) or
     * a charge point that has become unreachable. All in epoch millis, {@code 0} means "never".
     */
    private volatile long lastPollAttemptMs = 0;
    private volatile long lastPollFinishMs = 0;
    private volatile long lastPollSuccessMs = 0;

    /**
     * States to set/write. Indexed per socket. Updated from the event loop, written from the modbus thread.
     */
    private final Map<Integer, SetState> setStates = new ConcurrentHashMap<>();

    /**
     * Last state read. Indexed per socket. Updated from the modbus thread, read from the event loop.
     */
    private final Map<Integer, Map<Integer, Object>> socketStatus = new ConcurrentHashMap<>();

    /**
     * Last socket measurement read. Indexed per socket. Updated from the modbus thread, read from the event loop.
     */
    private final Map<Integer, Map<Integer, Object>> socketMeasurement = new ConcurrentHashMap<>();


    private record SetState(boolean enabled, float maxCurrent, int numPhases) {
    }

    AlfenModbusClient(Vertx vertx, String name, ModbusTcpClient client, MqttPublisher mqttPublisher, MqttHandler mqttListener, WriterConfig writerConfig, SocConfig socConfig) {
        this.vertx = vertx;
        this.client = client;
        this.name = name;
        this.mqttPublisher = mqttPublisher;
        this.mqttListener = mqttListener;
        this.writerConfig = writerConfig;
        this.socConfig = socConfig;
        this.modbusExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "modbus-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    public AlfenModbusClient(Vertx vertx, AlfenConfig.Device deviceConfig, MqttPublisher mqttPublisher, MqttHandler mqttListener, WriterConfig writerConfig, SocConfig socConfig) {
        this(vertx, deviceConfig.name(), createClient(deviceConfig), mqttPublisher, mqttListener, writerConfig, socConfig);
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
            pollTimerId = vertx.setPeriodic(0, Duration.ofSeconds(1).toMillis(), this::poll);
//            if (writeEnabled) {
//                LOG.info("Startup: write enabled");
//                vertx.setPeriodic(0, Duration.ofSeconds(10).toMillis(), this::pollWrite);
//            } else {
//                LOG.info("Startup: write disabled");
//            }
        }
    }

    private void runOnModbusThread(Runnable task) {
        try {
            modbusExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.debug("Modbus client {} is closed, dropping task", name);
        }
    }

    @Override
    public void close() {
        if (pollTimerId != -1) {
            vertx.cancelTimer(pollTimerId);
        }
        try {
            client.disconnect();
        } catch (ModbusExecutionException e) {
            LOG.debug("Error stopping modbus client", e);
        }
        writers.values().forEach(AlfenModbusWriter::close);
        writers.clear();
        socEstimators.values().forEach(SocEstimator::close);
        socEstimators.clear();
        modbusExecutor.shutdownNow();
    }

    public String getName() {
        return name;
    }

    private void poll(long l) {
        LOG.debug("Polling...");
        lastPollAttemptMs = System.currentTimeMillis();
        runOnModbusThread(() -> {
            try {
                pollRead();
                readCount++;
                errorCount = 0;
                lastPollSuccessMs = System.currentTimeMillis();
            } catch (Exception e) {
                errorCount++;
                LOG.warn("Error polling of modbus client", e);
                if (errorCount > 3) {
                    LOG.error("3 times in a row a read error, quitting");
                    Quarkus.asyncExit(100);
                }
            } finally {
                lastPollFinishMs = System.currentTimeMillis();
            }
        });
    }

    void pollRead() {
        readData();
    }

    /**
     * Millis since the last fully successful poll, or {@code -1} if none has succeeded yet.
     */
    public long msSinceLastSuccessfulPoll() {
        return lastPollSuccessMs == 0 ? -1 : System.currentTimeMillis() - lastPollSuccessMs;
    }

    /**
     * Millis the current poll iteration has been running without finishing. A value that keeps
     * growing means the blocking worker is stuck (deadlock / hung socket read).
     */
    public long pollStallMs() {
        long attempt = lastPollAttemptMs;
        if (attempt == 0 || attempt <= lastPollFinishMs) {
            return 0;
        }
        return System.currentTimeMillis() - attempt;
    }

    /**
     * Whether the poll loop has ever been scheduled (i.e. the client connected and started polling).
     */
    public boolean isPolling() {
        return lastPollAttemptMs != 0;
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
        boolean productOk = readData(ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC, true).isPresent();
        Optional<Map<Integer, Object>> stationStatus = readData(ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC, true);
        if (!productOk && stationStatus.isEmpty()) {
            // Every read failed (charge point unreachable / connection dead). Propagate so the poll
            // loop counts consecutive errors and can bail out for a restart, and so this iteration
            // is not recorded as a successful poll.
            throw new IllegalStateException("All modbus reads failed for " + name);
        }
        stationStatus.ifPresent(values -> {
            Object nrOfSockets = values.get(ModbusConst.ID_NR_OF_SOCKETS);
            if (nrOfSockets != null) {
                int nrOfSocketsInt = (int) nrOfSockets;
                LOG.debug("NrOfSockets: {}", nrOfSocketsInt);
                for (int i = 1; i <= nrOfSocketsInt; ++i) {
                    final int socket = i;
                    readData(ModbusConst.SOCKET_MEASUREMENT, i, true)
                            .ifPresent(s -> socketMeasurement.put(socket, s));
                    readData(ModbusConst.STATUS, i, true)
                            .ifPresent(s -> {
                                socketStatus.put(socket, s);
                                publishVehicleConnected(socket, s);
                            });
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
        if (writerConfig.dryRun()) {
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
            writers.put(s, new AlfenModbusWriter(vertx, this, name, s, mqttListener, writerConfig));
            int socket = s;
            socConfig.batteryCapacity().ifPresent(capacity ->
                    socEstimators.put(socket, new SocEstimator(vertx, this, name, socket, mqttListener, socConfig, capacity)));

            Map<String, Component> components = ModbusConst.SOCKET_MEASUREMENT.items().stream()
                    .filter(i -> i.discoveryInfo() != null)
                    .map(i -> new Sensor.Builder()
                            .withName(i.name())
                            .withUniqueId("socket_measurement_" + i.start())
                            .withDeviceClass(i.discoveryInfo().deviceClass())
                            .withStateClass(i.discoveryInfo().stateClass())
                            .withUnitOfMeasurement(i.discoveryInfo().unit())
                            .withSuggestedDisplayPrecision(i.discoveryInfo().precision())
                            .withValueTemplate("{{ value_json.S" + i.start() + " }}")
                            .build()
                    ).collect(Collectors.toMap(Component::getUniqueId, i -> i, (a, b) -> a, HashMap::new));
            String uuid = serial + "-" + s;
            controllerComponents(uuid, s).forEach(c -> components.put(c.getUniqueId(), c));
            BinarySensor connected = new BinarySensor.Builder()
                    .withName("Car connected")
                    .withUniqueId(uuid + "_connected")
                    .withDeviceClass(BinarySensor.DeviceClass.PLUG)
                    .withStateTopic(MqttPublisher.connectedTopic(name, s))
                    .build();
            components.put(connected.getUniqueId(), connected);
            if (socConfig.batteryCapacity().isPresent()) {
                Sensor soc = new Sensor.Builder()
                        .withName("Estimated battery")
                        .withUniqueId(uuid + "_soc_estimate")
                        .withDeviceClass(DeviceClass.BATTERY)
                        .withStateClass(StateClass.MEASUREMENT)
                        .withUnitOfMeasurement("%")
                        .withSuggestedDisplayPrecision(1)
                        .withStateTopic(SocEstimator.estimateTopic(name, s))
                        .withValueTemplate("{{ value_json.estimate }}")
                        .build();
                components.put(soc.getUniqueId(), soc);
            }
            Discovery discovery = new Discovery(
                    new Discovery.Device(
                            uuid,
                            "mouse256",
                            "alfen",
                            "alfen-mqtt",
                            null
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

    /**
     * Discovery for the charging controller of a socket: the charge mode select and its status.
     * These have their own state topics, overriding the device-level one.
     */
    private List<Component> controllerComponents(String uuid, int socket) {
        String statusTopic = AlfenModbusWriter.statusTopic(name, socket);
        return List.of(
                new Select.Builder()
                        .withName("Charge mode")
                        .withUniqueId(uuid + "_controller_mode")
                        .withCommandTopic(AlfenModbusWriter.modeCommandTopic(name, socket))
                        .withStateTopic(AlfenModbusWriter.modeStateTopic(name, socket))
                        .withOptions(Arrays.stream(AlfenModbusWriter.ChargeMode.values()).map(Enum::name).toList())
                        .withIcon("mdi:ev-station")
                        .build(),
                new BinarySensor.Builder()
                        .withName("Controller charging")
                        .withUniqueId(uuid + "_controller_charging")
                        .withDeviceClass(BinarySensor.DeviceClass.BATTERY_CHARGING)
                        .withStateTopic(statusTopic)
                        .withValueTemplate("{{ 'ON' if value_json.charging else 'OFF' }}")
                        .build(),
                new Sensor.Builder()
                        .withName("Controller target current")
                        .withUniqueId(uuid + "_controller_target_current")
                        .withDeviceClass(DeviceClass.CURRENT)
                        .withUnitOfMeasurement("A")
                        .withSuggestedDisplayPrecision(1)
                        .withStateTopic(statusTopic)
                        .withValueTemplate("{{ value_json.targetCurrent }}")
                        .build(),
                new Sensor.Builder()
                        .withName("Controller phases")
                        .withUniqueId(uuid + "_controller_phases")
                        .withSuggestedDisplayPrecision(0)
                        .withStateTopic(statusTopic)
                        .withValueTemplate("{{ value_json.phases }}")
                        .build(),
                new Sensor.Builder()
                        .withName("Controller state")
                        .withUniqueId(uuid + "_controller_state")
                        .withStateTopic(statusTopic)
                        .withValueTemplate("{{ value_json.state }}")
                        .build(),
                new Sensor.Builder()
                        .withName("Controller timer end")
                        .withUniqueId(uuid + "_controller_timer_end")
                        .withDeviceClass(DeviceClass.TIMESTAMP)
                        .withStateTopic(statusTopic)
                        // HA turns a rendered "None" into unknown: no timer running
                        .withValueTemplate("{{ value_json.timerEnd if value_json.timerEnd else None }}")
                        .build()
        );
    }

    private void pollWrite() {

    }


    public void disable(int socket) {
        SetState current = setStates.get(socket);
        if (current == null || current.enabled()) {
            LOG.info("Set socket {} to disabled", socket);
            setStates.put(socket, new SetState(false, 0, 1));

        }
        runOnModbusThread(this::writeData);
    }

    public void setState(int socket, float maxCurrent, int numPhases) {
        LOG.info("Set socket {} to enabled, maxCurrent: {}, numPhases: {}", socket, maxCurrent, numPhases);
        setStates.put(socket, new SetState(true, maxCurrent, numPhases));

        runOnModbusThread(this::writeData);
    }

    /**
     * Whether a vehicle is connected, derived from the IEC 61851 Mode 3 state (register 1201):
     * B, C and D (with or without PWM) mean a vehicle is connected; A means none, E and F are
     * error states. Empty when the state is unknown or not recognised.
     */
    static Optional<Boolean> vehicleConnected(Object mode3State) {
        if (!(mode3State instanceof String state) || state.isEmpty()) {
            return Optional.empty();
        }
        return switch (state.charAt(0)) {
            case 'B', 'C', 'D' -> Optional.of(true);
            case 'A', 'E', 'F' -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    public Optional<Boolean> isVehicleConnected(int socket) {
        Map<Integer, Object> status = socketStatus.get(socket);
        return status == null ? Optional.empty() : vehicleConnected(status.get(ModbusConst.ID_MODE3_STATE));
    }

    private void publishVehicleConnected(int socket, Map<Integer, Object> status) {
        Object mode3State = status.get(ModbusConst.ID_MODE3_STATE);
        Optional<Boolean> connected = vehicleConnected(mode3State);
        if (connected.isEmpty()) {
            LOG.debug("Unknown mode 3 state for {} ({}): {}", name, socket, mode3State);
            return;
        }
        mqttPublisher.sendModbusConnected(name, socket, connected.get());
    }

    public Optional<Double> getSocketRealEnergyDeliveredSum(int socket) {
        Map<Integer, Object> measure = socketMeasurement.get(socket);
        if (measure == null) {
            return Optional.empty();
        }
        Double energy = (Double) measure.get(ModbusConst.ITEM_REAL_ENERGY_DELIVERED_SUM.start());
        return energy == null || energy.isNaN() ? Optional.empty() : Optional.of(energy);
    }

    public Optional<Integer> getSocketRealPowerSum(int socket) {
        Map<Integer, Object> measure = socketMeasurement.get(socket);
        if (measure == null) {
            return Optional.empty();
        }
        return Optional.of(Math.round((Float) measure.get(ModbusConst.ITEM_REAL_POWER_SUM.start())));
    }

    /**
     * Write the desired state of every socket. Runs on the modbus thread and relies on the socket
     * status of the last poll (at most ~1s old) instead of re-reading everything before each write.
     */
    private synchronized void writeData() {
        if (writerConfig.dryRun()) {
            return;
        }
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
