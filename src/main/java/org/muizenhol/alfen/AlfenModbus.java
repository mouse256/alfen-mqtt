package org.muizenhol.alfen;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersResponse;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.muizenhol.homeassistant.Discovery;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class AlfenModbus {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    AlfenConfig alfenConfig;

    @Inject
    MqttPublisher mqttPublisher;

    @Inject
    MqttListener mqttListener;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "modbus.write_enabled", defaultValue = "false")
    boolean writeEnabled;

    @Inject
    EvccHandler evccHandler;

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
                new AlfenModbusClient(vertx, deviceConfig, writeEnabled, mqttPublisher, mqttListener)));

    }

    public void stop() {
        clients.forEach((name, client) -> client.close());
        clients.clear();
    }




    public void handleWrite(String chargerName, int socket, String key, String payload) {
        if (!writeEnabled) {
            return;
        }

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
    }

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

}
