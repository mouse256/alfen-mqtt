package org.muizenhol.alfen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.muizenhol.alfen.data.Categories;
import org.muizenhol.alfen.data.PropertyCatRsp;
import org.muizenhol.alfen.data.PropertyParsed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class AlfenController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final List<String> CATEGORIES = List.of("meter1", "temp", "generic2");
    @Inject
    ObjectMapper objectMapper;

    @Inject
    AlfenConfig alfenConfig;

    @Inject
    Vertx vertx;

    @Inject
    MqttPublisher mqttPublisher;

    private Properties ids;
    private Map<String, AlfenConnection> devices = Collections.emptyMap();
    boolean init = false;
    private long timerId;

    public void start() {
        if (init) {
            throw new IllegalStateException("init already done");
        }
        init = true;
        List<AlfenConfig.Device> deviceConfig = alfenConfig.devices().stream()
                .filter(d -> d.type() == AlfenConfig.DeviceType.HTTP)
                .toList();
        if (deviceConfig.isEmpty()) {
            LOG.info("No Alfen HTTP devices configured");
            return;
        }
        LOG.info("Startup: endpoints: {}", deviceConfig.stream()
                .map(e -> e.name() + ": " + e.endpoint())
                .collect(Collectors.toList()));
        ids = new Properties();
        try {
            ids.load(this.getClass().getResourceAsStream("/ids.properties"));
        } catch (IOException e) {
            throw new IllegalStateException("Can't load ids.properties");
        }

        devices = alfenConfig.devices().stream()
                .collect(Collectors.toMap(AlfenConfig.Device::name,
                        d -> new AlfenConnection(d, objectMapper)));

        timerId = vertx.setPeriodic(100, Duration.ofSeconds(5).toMillis(),
                e -> deviceConfig.forEach(
                        device -> CATEGORIES.forEach(
                                category -> poll(device, category))));
    }

    public void stop() {
        vertx.cancelTimer(timerId);
        devices.clear();
        init = false;
    }

    private AlfenConnection getConn(String device) {
        AlfenConnection conn = devices.get(device);
        if (conn == null) {
            throw new NotFoundException("Device with name " + device + " not found");
        }
        return conn;
    }

    public Optional<Categories> getCategories(String device) {
        return getConn(device).getCategories();
    }

    public List<PropertyParsed> getProperties(String device, String category) {
        AlfenConnection conn = getConn(device);
        Optional<Categories> categories = conn.getCategories();
        if (categories.filter(cat -> cat.categories().contains(category)).isEmpty()) {
            throw new BadRequestException("Unknown category " + category + " valid: " +
                    categories.map(cat -> String.join(",", cat.categories())).orElse("[N/A]"));
        }

        int offset = 0;
        PropertyCatRsp dataFull = null;
        do {
            String offsetStr = (offset == 0 ? "" : "&offset=" + offset);
            PropertyCatRsp data = conn.getData(PropertyCatRsp.class, "prop?cat=" + category + offsetStr).orElseThrow(() -> new IllegalStateException("can't fetch properties"));
            if (dataFull == null) {
                dataFull = data;
            } else {
                //merge
                if (dataFull.total() != data.total()) {
                    throw new IllegalStateException("Totals don't match");
                }
                dataFull.properties().addAll(data.properties());
            }
            if (dataFull.properties().size() == dataFull.total()) {
                break;
            }
            offset = dataFull.properties().size();
        } while (true);

        return convertProperties(dataFull);
    }

    private List<PropertyParsed> convertProperties(PropertyCatRsp propertyCatRsp) {
        return propertyCatRsp.properties().stream()
                .map(p -> new PropertyParsed(ids.getProperty(p.id(), "UNKNOWN"), p.id(), convertValues(p.type(), p.value())))
                .collect(Collectors.toList());
    }

    private Object convertValues(int type, String value) {
        return switch (type) {
            case 2, 5 -> Integer.parseInt(value);
            case 8 -> Double.parseDouble(value);
            default -> value;
        };
    }

    private void poll(AlfenConfig.Device device, String category) {
        LOG.debug("Polling device {} (category {})", device, category);
        try {
            List<PropertyParsed> properties = getProperties(device.name(), category);
            LOG.debug("Got {} properties", properties.size());
            mqttPublisher.send(device.name(), category, properties);
        } catch (Exception e) {
            LOG.warn("Can't fetch category {} for device {}: {}", category, device, e.getMessage());
        }
    }

}
