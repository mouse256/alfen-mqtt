package org.muizenhol.alfen;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-socket estimate of the car's battery level (evcc-style). The car's own level arrives slowly
 * over MQTT on {@link SocConfig#carSocTopic()}; in between, the energy delivered by the charger (register
 * "Real Energy Delivered Sum") since the last reported level is added:
 * {@code estimate = carSoc + deliveredKWh * efficiency / capacity * 100}, capped at 100.
 * Each new reported level resets the estimate to that level.
 */
public class SocEstimator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final AlfenModbusClient client;
    private final String chargerName;
    private final int socket;
    private final MqttHandler mqttHandler;
    private final double capacityKWh;
    private final double efficiency;
    private final Supplier<Instant> clock;
    private final Vertx vertx;
    private final long timerId;

    // Last level reported by the car, with the charger's delivered energy at that moment.
    private Double carSoc = null;
    private Instant carSocReceived = null;
    private Double anchorEnergyWh = null;

    /**
     * Published on {@link #estimateTopic}. {@code carSocReceived} is an ISO-8601 instant.
     */
    public record SocStatus(double estimate, double carSoc, String carSocReceived, double energySinceWh) {
    }

    /**
     * Topic on which the {@link SocStatus} is published.
     */
    public static String estimateTopic(String chargerName, int socket) {
        return "alfen/car/" + chargerName + "/" + socket + "/soc_estimate";
    }

    public SocEstimator(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler,
                        SocConfig config, double capacityKWh) {
        this(vertx, client, chargerName, socket, mqttHandler, config, capacityKWh, Instant::now);
    }

    public SocEstimator(Vertx vertx, AlfenModbusClient client, String chargerName, int socket, MqttHandler mqttHandler,
                        SocConfig config, double capacityKWh, Supplier<Instant> clock) {
        this.vertx = vertx;
        this.client = client;
        this.chargerName = chargerName;
        this.socket = socket;
        this.mqttHandler = mqttHandler;
        this.capacityKWh = capacityKWh;
        this.efficiency = config.efficiency();
        this.clock = clock;
        String topic = config.carSocTopic();
        LOG.info("SoC estimation for {} ({}) enabled: capacity {} kWh, efficiency {}, car level expected on {}",
                chargerName, socket, capacityKWh, efficiency, topic);
        mqttHandler.register(Pattern.compile(Pattern.quote(topic)), topic, this::handleCarSoc);
        timerId = vertx == null ? 0 : vertx.setPeriodic(config.interval().toMillis(), id -> update());
    }

    @Override
    public void close() {
        if (vertx != null) {
            vertx.cancelTimer(timerId);
        }
    }

    private void handleCarSoc(String topic, Matcher m, String payload) {
        double soc;
        try {
            soc = Double.parseDouble(payload.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Can't parse car battery level on {}: {}", topic, payload);
            return;
        }
        Optional<Double> energy = client.getSocketRealEnergyDeliveredSum(socket);
        synchronized (this) {
            LOG.info("Car battery level for {} ({}) reported: {}% (estimate was {})", chargerName, socket, soc,
                    estimate().map(e -> String.format("%.1f%%", e)).orElse("unknown"));
            carSoc = soc;
            carSocReceived = clock.get();
            // When no measurement is available yet, anchor on the first one in update().
            anchorEnergyWh = energy.orElse(null);
        }
        update();
    }

    synchronized Optional<Double> estimate() {
        if (carSoc == null) {
            return Optional.empty();
        }
        Optional<Double> energy = client.getSocketRealEnergyDeliveredSum(socket);
        if (energy.isEmpty()) {
            return Optional.of(carSoc);
        }
        if (anchorEnergyWh == null || energy.get() < anchorEnergyWh) {
            // first measurement after the car level arrived, or the meter was reset
            anchorEnergyWh = energy.get();
        }
        double energySinceWh = energy.get() - anchorEnergyWh;
        return Optional.of(Math.min(100.0, carSoc + energySinceWh / 1000.0 * efficiency / capacityKWh * 100.0));
    }

    void update() {
        SocStatus status;
        synchronized (this) {
            Optional<Double> estimate = estimate();
            if (estimate.isEmpty()) {
                LOG.debug("No car battery level received yet for {} ({})", chargerName, socket);
                return;
            }
            double energySinceWh = anchorEnergyWh == null ? 0
                    : client.getSocketRealEnergyDeliveredSum(socket).map(e -> e - anchorEnergyWh).orElse(0.0);
            status = new SocStatus(Math.round(estimate.get() * 10) / 10.0, carSoc, carSocReceived.toString(), energySinceWh);
        }
        try {
            mqttHandler.publishJson(estimateTopic(chargerName, socket), status);
        } catch (Exception e) {
            LOG.debug("Could not publish SoC estimate", e);
        }
    }
}
