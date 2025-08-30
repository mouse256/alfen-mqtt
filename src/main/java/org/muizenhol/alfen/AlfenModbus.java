package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class AlfenModbus {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    AlfenConfig alfenConfig;

    @Inject
    MqttPublisher mqttPublisher;

    @Inject
    MqttHandler mqttListener;

    @Inject
    WriterConfig writerConfig;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "modbus.write_enabled", defaultValue = "false")
    boolean writeEnabled;

    final Map<String, AlfenModbusClient> clients = new HashMap<>();

    private record SetState(boolean enabled, float maxCurrent) {
    }

    private final Map<ModbusTcpClient, Map<Integer, SetState>> setStates = new HashMap<>();

    public void start() {
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

        deviceConfigs.forEach(deviceConfig -> clients.put(deviceConfig.name(),
                new AlfenModbusClient(vertx, deviceConfig, writeEnabled, mqttPublisher, mqttListener, writerConfig)));

    }

    public void stop() {
        clients.forEach((name, client) -> client.close());
        clients.clear();
    }

}
