package org.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.acme.data.Categories;
import org.acme.data.Login;
import org.acme.data.PropertyCatRsp;
import org.acme.data.PropertyParsed;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

@ApplicationScoped
public class AlfenController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    @Inject
    private ObjectMapper objectMapper;

    @ConfigProperty(name = "alfen.endpoint")
    private String endpoint;
    @ConfigProperty(name = "alfen.username")
    private String username;
    @ConfigProperty(name = "alfen.password")
    private String password;

    private Properties ids;

    public void onStart(@Observes StartupEvent startupEvent) {
        LOG.info("Startup: endpoint: {}", endpoint);
        ids = new Properties();
        try {
            ids.load(this.getClass().getResourceAsStream("/ids.properties"));
        } catch (IOException e) {
            throw new IllegalStateException("Can't load ids.properties");
        }
    }

    private HttpClient httpClient;
    private Categories categoriesCache;

    private void login() {
        try {
            LOG.info("Login");
            if (username.isEmpty()) {
                throw new IllegalArgumentException("username not set");
            }
            if (password.isEmpty()) {
                throw new IllegalArgumentException("password not set");
            }
            Login login = new Login(username, password, "alfen-mqtt");
            String loginStr = objectMapper.writeValueAsString(login);
            URI uri = new URI(endpoint + "/api/login");
            LOG.info("Login URI: {}", uri);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .headers("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginStr))
                    .build();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, SecureRandom.getInstanceStrong());

            httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();

            HttpResponse<String> response = httpClient
                    .send(request, HttpResponse.BodyHandlers.ofString());
            /*LOG.info("RSP: {} -- {}", response.statusCode(), response.body());
            response.headers().map().forEach((k,v) -> {
                LOG.info("H: {}: {}", k, v);
            });*/
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.info("Login OK");
            } else {
                LOG.warn("Login error");
                LOG.info("RSP: {} -- {}", response.statusCode(), response.body());
                response.headers().map().forEach((k, v) -> {
                    LOG.info("H: {}: {}", k, v);
                });
                reset();
            }


        } catch (Exception e) {
            LOG.error("Error executing login", e);
        }
    }

    private void reset() {
        httpClient = null;
        categoriesCache = null;
    }

    private void checkLogin() {
        if (httpClient == null) {
            login();
        }
        if (httpClient == null) {
            throw new IllegalStateException("Login failed");
        }
    }

    private <T> Optional<T> getData(Class<T> valueType, String urlPath) {
        try {
            URI uri = new URI(endpoint + "/api/" + urlPath);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET().build();

            LOG.debug("GETting {}", uri);
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            LOG.debug("RSP: {}", response.statusCode());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.debug("RSP: {} -- {}", response.statusCode(), new String(response.body()));
                return Optional.of(objectMapper.readValue(response.body(), valueType));
            } else {
                LOG.warn("Error fetching {}: http {}\n{}", uri, response.statusCode(), new String(response.body()));
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Can't fetch " + urlPath, e);
        }
    }

    public Optional<Categories> getCategories() {
        checkLogin();

        if (categoriesCache != null) {
            return Optional.of(categoriesCache);
        }
        getData(Categories.class, "categories").ifPresent(it -> categoriesCache = it);
        return Optional.of(categoriesCache);
    }

    public List<PropertyParsed> getProperties(String category) {
        checkLogin();
        Optional<Categories> categories = getCategories();
        if (categories.filter(cat -> cat.categories().contains(category)).isEmpty()) {
            throw new BadRequestException("Unknown category " + category + " valid: " +
                    categories.map(cat -> String.join(",", cat.categories())).orElse("[N/A]"));
        }

        int offset = 0;
        PropertyCatRsp dataFull = null;
        do {
            String offsetStr = (offset == 0 ? "" : "&offset=" + offset);
            PropertyCatRsp data = getData(PropertyCatRsp.class, "prop?cat=" + category + offsetStr).orElseThrow(() -> new IllegalStateException("can't fetch properties"));
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

    TrustManager trustManager = new X509ExtendedTrustManager() {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{};
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }
    };
}
