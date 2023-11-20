package org.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.acme.data.Login;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    public void onStart(@Observes StartupEvent startupEvent) {
        LOG.info("Startup: endpoint: {}", endpoint);
        login();
    }

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
            LOG.info("login: {}", loginStr);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI("https://" + endpoint + "/api/login"))
                    .headers("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginStr))
                    .build();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, SecureRandom.getInstanceStrong());

            HttpClient client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .build();

            HttpResponse<String> response = client
                    .send(request, HttpResponse.BodyHandlers.ofString());
            LOG.info("RSP: {} -- {}", response.statusCode(), response.body());
            response.headers().map().forEach((k,v) -> {
                LOG.info("H: {}: {}", k, v);
            });

            HttpRequest request2 = HttpRequest.newBuilder()
                    .uri(new URI("https://" + endpoint + "/api/categories"))
                    .GET().build();
            HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
            LOG.info("RSP2: {} -- {}", response2.statusCode(), response2.body());
            response2.headers().map().forEach((k,v) -> {
                LOG.info("H: {}: {}", k, v);
            });

        } catch (Exception e) {
            LOG.error("kapot", e);
            throw new RuntimeException(e);
        }
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
