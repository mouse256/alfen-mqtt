package org.muizenhol.alfen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.muizenhol.alfen.data.Categories;
import org.muizenhol.alfen.data.Login;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.lang.invoke.MethodHandles;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;

public class AlfenConnection {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final AlfenConfig.Device config;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private Categories categoriesCache;

    public AlfenConnection(AlfenConfig.Device config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    private void login() {
        try {
            LOG.info("Login");
            if (config.username().isEmpty()) {
                throw new IllegalArgumentException("username not set for " + config.name());
            }
            if (config.password().isEmpty()) {
                throw new IllegalArgumentException("password not set for " + config.name());
            }
            Login login = new Login(
                    config.username().orElseThrow(() -> new IllegalArgumentException("username not set for " + config.name())),
                    config.password().orElseThrow(() -> new IllegalArgumentException("password not set for " + config.name())),
                    "alfen-mqtt");
            String loginStr = objectMapper.writeValueAsString(login);
            URI uri = new URI(config.endpoint() + "/api/login");
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

    public <T> Optional<T> getData(Class<T> valueType, String urlPath) {
        try {
            URI uri = new URI(config.endpoint() + "/api/" + urlPath);
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
