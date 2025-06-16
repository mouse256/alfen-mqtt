package org.muizenhol.alfen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AlfenModbusWriter implements AutoCloseable, MqttHandler.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String KEY_MODE = "mode";
    private final AlfenModbusClient client;
    private final int socket;
    private final String chargerName;
    private final MqttHandler mqttHandler;
    private final PowerUsage powerUsage = new PowerUsage();
    private final PowerUsage powerSolar = new PowerUsage();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public enum ChargeMode {
        NO_CHARGE,
        PV_ONLY,
        PV_AND_MIN,
        FAST
    }

    private static class PowerUsage {
        int produced = 0;
        int consumed = 0;
        Instant lastUpdate = Instant.EPOCH;
    }

    //private ChargeMode chargeMode = ChargeMode.PV_ONLY; //default mode

    public AlfenModbusWriter(AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttListener) {
        LOG.info("Creating AlfenModbusWriter for {} (socket {})", chargerName, socket);
        this.client = client;
        this.socket = socket;
        this.chargerName = chargerName;
        this.mqttHandler = mqttListener;
        // topic: alfen/set/<chargername>/<socket>/<key>
        Pattern pattern = Pattern.compile("alfen/set/" + chargerName + "/(\\d+)/(.*)");
        mqttListener.register(pattern, "alfen/set/+/+/+", this);

        String topicPowerConsumed = "slimmelezer/sensor/power_consumed/state";
        String topicPowerProduced = "slimmelezer/sensor/power_produced/state";
        String topicSolar = "serialread/power";
        mqttListener.register(Pattern.compile(topicPowerConsumed), topicPowerConsumed, (topic, matchedTopic, payload) -> {
            int power = (int) (Double.parseDouble(payload) * 1000);
            LOG.debug("Received power consumed message: {} -- {}", payload, power);
            synchronized (powerUsage) {
                powerUsage.consumed = power;
                powerUsage.lastUpdate = Instant.now();
            }
        });
        mqttListener.register(Pattern.compile(topicPowerProduced), topicPowerProduced, (topic, matchedTopic, payload) -> {
            int power = (int) (Double.parseDouble(payload) * 1000);
            LOG.debug("Received power produced message: {} -- {}", payload, power);
            synchronized (powerUsage) {
                powerUsage.produced = power;
                powerUsage.lastUpdate = Instant.now();
            }
        });

        mqttListener.register(Pattern.compile(topicSolar), topicSolar, (topic, matchedTopic, payload) -> {
            try {
                JsonNode jsonNode = objectMapper.readTree(payload);
                double power = jsonNode.get("data").get("Power_real_1_3").asDouble();
                LOG.debug("Received power solar message: {} -- {}", payload, power);
                synchronized (powerSolar) {
                    powerSolar.produced = (int) power;
                    powerSolar.lastUpdate = Instant.now();
                }
            } catch (JsonProcessingException e) {
                LOG.warn("Json parse exception", e);
            }
        });
    }

    @Override
    public void close() {

    }

    @Override
    public void handleMessage(String topic, Matcher m, String payload) {

        LOG.info("Handling msg");
        int socket;
        try {
            socket = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse value as int on topic {}: {}", topic, m.group(1));
            return;
        }
        String key = m.group(2);
        LOG.info("Incoming set message for {} ({}): {} -> {}", chargerName, socket, key, payload);
        if (key.equalsIgnoreCase(KEY_MODE)) {
            try {
                ChargeMode chargeMode = ChargeMode.valueOf(payload.toUpperCase());
                switch (chargeMode) {
                    case NO_CHARGE -> client.disable(socket);
                    case PV_ONLY -> client.setState(socket, 6, 1);
                    case PV_AND_MIN -> client.setState(socket, 6, 1);
                    case FAST -> client.setState(socket, 6, 3);
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown charge mode for {}: {}", topic, payload);
            }
        } else {
            LOG.warn("Invalid mode: {}", topic);
        }
    }
}