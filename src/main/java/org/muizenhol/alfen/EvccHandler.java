package org.muizenhol.alfen;

import io.smallrye.reactive.messaging.mqtt.ReceivingMqttMessageMetadata;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.muizenhol.alfen.data.Evcc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

@Dependent
public class EvccHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Pattern PATTERN_EVCC = Pattern.compile("alfen/evcc/set/([^/]+)/(\\d+)/(.*)");

    @Inject
    MqttPublisher mqttPublisher;

    @Inject
    AlfenModbus alfenModbus;

    public void writeEvcc(String name, int addr, Optional<Map<Integer, Object>> statusOpt, Optional<Map<Integer, Object>> socketMeasurementOpt) {
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

    private void handleEvccNoReturn(Message<String> evcc, ReceivingMqttMessageMetadata meta) {

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
        alfenModbus.handleWrite(chargerName, socket, key, evcc.getPayload());

    }
}
