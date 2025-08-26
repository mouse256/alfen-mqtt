package org.muizenhol.alfen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AlfenModbusWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String KEY_MODE = "mode";
    private final AlfenModbusClient client;
    private final String chargerName;
    private final PowerUsage powerUsage = new PowerUsage();
    private final PowerUsage powerSolar = new PowerUsage();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Vertx vertx;
    private ChargeMode chargeMode = ChargeMode.OFF;
    private final long timerId;
    private final int socket;
    public static final String TOPIC_POWER_CONSUMED = "slimmelezer/sensor/power_consumed/state";
    public static final String TOPIC_POWER_PRODUCED = "slimmelezer/sensor/power_produced/state";
    public static final String TOPIC_SOLAR = "serialread/power";
    public static final String TOPIC_SET = "alfen/set/+/+/+";

    public enum ChargeMode {
        OFF,
        PV_ONLY,
        PV_AND_MIN,
        FAST
    }

    private static class PowerUsage {
        int produced = 0;
        int consumed = 0;
        Instant lastUpdate = Instant.EPOCH;
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttListener) {
        this(vertx, client, chargerName, socket, mqttListener, true);
    }

    public AlfenModbusWriter(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttListener, boolean enableTimer) {
        LOG.info("Creating AlfenModbusWriter for {} (socket {})", chargerName, socket);
        this.client = client;
        this.vertx = vertx;
        this.socket = socket;
        this.chargerName = chargerName;
        // topic: alfen/set/<chargername>/<socket>/<key>
        Pattern pattern = Pattern.compile("alfen/set/" + chargerName + "/(\\d+)/(.*)");

        mqttListener.register(pattern, TOPIC_SET, this::handleMessage);
        mqttListener.register(Pattern.compile(TOPIC_POWER_CONSUMED), TOPIC_POWER_CONSUMED, this::handlePowerConsumed);
        mqttListener.register(Pattern.compile(TOPIC_POWER_PRODUCED), TOPIC_POWER_PRODUCED, this::handlePowerProduced);
        mqttListener.register(Pattern.compile(TOPIC_SOLAR), TOPIC_SOLAR, this::handleSolar);
        if (enableTimer) {
            timerId = vertx.setPeriodic(5_000, this::update);
        } else {
            timerId = 0;
        }
    }

    @Override
    public void close() {
        vertx.cancelTimer(timerId);
    }

    private void handleMessage(String topic, Matcher m, String payload) {

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
                chargeMode = ChargeMode.valueOf(payload.toUpperCase());

            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown charge mode for {}: {}", topic, payload);
            }
        } else {
            LOG.warn("Invalid mode: {}", topic);
        }
    }

    private void handlePowerConsumed(String topic, Matcher matchedTopic, String payload) {
        int power = (int) (Double.parseDouble(payload) * 1000);
        LOG.debug("Received power consumed message: {} -- {}", payload, power);
        synchronized (powerUsage) {
            powerUsage.consumed = power;
            powerUsage.lastUpdate = Instant.now();
        }
    }

    private void handlePowerProduced(String topic, Matcher matchedTopic, String payload) {
        int power = (int) (Double.parseDouble(payload) * 1000);
        LOG.debug("Received power produced message: {} -- {}", payload, power);
        synchronized (powerUsage) {
            powerUsage.produced = power;
            powerUsage.lastUpdate = Instant.now();
        }
    }

    private void handleSolar(String topic, Matcher matchedTopic, String payload) {
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
    }

    void update(Long aLong) {
        LOG.debug("update");
        int powerGrid;
        synchronized (powerUsage) {
            powerGrid = powerUsage.produced - powerUsage.consumed;
        }
        Optional<Integer> chargerPowerConsumedOpt = client.getSocketRealPowerSum(socket);
        if (chargerPowerConsumedOpt.isEmpty()) {
            LOG.warn("No socket power measurement for socket {}", socket);
            return;
        }
        int chargerPowerConsumed = chargerPowerConsumedOpt.get();
        int powerAvailable = powerGrid + chargerPowerConsumed;
        LOG.debug("Power grid: {}, consumed: {}, available: {}", powerGrid, chargerPowerConsumed, powerAvailable);


        switch (chargeMode) {
            case OFF -> client.disable(socket);
            case PV_ONLY -> {
                if (powerAvailable > 300) {
                    double amps = Math.max(6.0, powerAvailable / 230.);
                    client.setState(socket, (float) amps, 1);
                } else {
                    client.disable(socket);
                }
            }
            case PV_AND_MIN -> client.setState(socket, 6, 1);
            case FAST -> client.setState(socket, 6, 3);
        }
    }
}