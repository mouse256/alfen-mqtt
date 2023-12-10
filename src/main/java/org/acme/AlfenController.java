package org.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.acme.data.Categories;
import org.acme.data.PropertyCatRsp;
import org.acme.data.PropertyParsed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

@ApplicationScoped
public class AlfenController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    @Inject
    private ObjectMapper objectMapper;

    @Inject
    private AlfenConfig alfenConfig;
    private Properties ids;
    private Map<String, AlfenConnection> devices;

    public void onStart(@Observes StartupEvent startupEvent) {
        LOG.info("Startup: endpoints: {}", alfenConfig.devices().stream()
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

}
