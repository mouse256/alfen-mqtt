package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class AlfenModbus {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    AlfenConfig alfenConfig;

    @Inject
    MqttPublisher mqttPublisher;

    @Inject
    Vertx vertx;

    private final List<ModbusTcpClient> clients = new ArrayList<>();

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
                client.connect();
                clients.add(client);

            } catch (Exception e) {
                LOG.warn("Error connecting to modbus client: {}", deviceConfig.endpoint(), e);
            }
        });

        vertx.setPeriodic(0, Duration.ofSeconds(5).toMillis(), this::poll);
    }

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        clients.forEach(clients -> {
            try {
                clients.disconnect();
            } catch (ModbusExecutionException e) {
                LOG.debug("Disconnect failed", e);
            }
        });
        clients.clear();
    }

    private void poll(long l) {
        LOG.debug("Polling...");
        clients.forEach(this::readData);
    }

    private void readData(ModbusTcpClient client) {
        readData(client, ModbusConst.PRODUCT_IDENTIFICATION, ModbusConst.ADDR_GENERIC);
        readData(client, ModbusConst.STATION_STATUS, ModbusConst.ADDR_GENERIC).ifPresent(values -> {
            Object nrOfSockets = values.get(ModbusConst.ID_NR_OF_SOCKETS);
            if (nrOfSockets != null) {
                int nrOfSocketsInt = (int) nrOfSockets;
                LOG.debug("NrOfSockets: {}", nrOfSocketsInt);
                for (int i = 1; i <= nrOfSocketsInt; ++i) {
                    readData(client, ModbusConst.SOCKET_MEASUREMENT, i);
                    readData(client, ModbusConst.STATUS, i);
                }
            } else {
                LOG.warn("Can't fetch number of sockets. Got null");
            }
        });
    }

    private Optional<Map<Integer, Object>> readData(ModbusTcpClient client, ModbusConst.Group group, int unitId) {
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
            mqttPublisher.sendModbus(group.name(), values, unitId);
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
}
