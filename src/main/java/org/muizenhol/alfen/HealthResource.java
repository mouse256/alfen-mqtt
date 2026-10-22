package org.muizenhol.alfen;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight liveness/health endpoint. Returns HTTP 200 while the application is doing useful work
 * and HTTP 503 once a subsystem has wedged (modbus poll loop deadlocked / stale, or the MQTT
 * connection is silently broken so every publish fails). Point a Docker {@code HEALTHCHECK},
 * Kubernetes liveness probe, systemd watchdog or {@code autoheal} at {@code /health/live} to get an
 * automatic restart when that happens.
 */
@Path("/health")
public class HealthResource {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    AlfenModbus alfenModbus;

    @Inject
    MqttHandler mqttHandler;

    @Inject
    HealthConfig healthConfig;

    public record Check(String name, boolean ok, String detail) {
    }

    public record HealthReport(String status, long uptimeMs, List<Check> checks) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return build();
    }

    @GET
    @Path("live")
    @Produces(MediaType.APPLICATION_JSON)
    public Response live() {
        return build();
    }

    @GET
    @Path("ready")
    @Produces(MediaType.APPLICATION_JSON)
    public Response ready() {
        return build();
    }

    private Response build() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        boolean inGrace = uptimeMs < healthConfig.startupGrace().toMillis();

        List<Check> checks = new ArrayList<>();
        checks.add(checkMqtt(inGrace));
        checks.addAll(checkModbus(inGrace));

        boolean up = checks.stream().allMatch(Check::ok);
        HealthReport report = new HealthReport(up ? "UP" : "DOWN", uptimeMs, checks);
        if (!up) {
            LOG.warn("Health check DOWN: {}", checks.stream().filter(c -> !c.ok()).toList());
        }
        return Response.status(up ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(report)
                .build();
    }

    private Check checkMqtt(boolean inGrace) {
        MqttHandler.Status s = mqttHandler.status();
        if (!s.enabled()) {
            return new Check("mqtt", true, "disabled");
        }
        if (inGrace) {
            return new Check("mqtt", true, "startup grace");
        }
        if (!s.connected()) {
            return new Check("mqtt", false, "not connected");
        }
        // Only treat "no acks" as a failure while we are actually still trying to publish; an idle
        // system that simply has nothing to send is fine.
        long staleLimit = healthConfig.mqttPublishStaleLimit().toMillis();
        boolean publishingRecently = s.msSinceLastPublishAttempt() >= 0
                && s.msSinceLastPublishAttempt() < staleLimit;
        if (publishingRecently && s.msSinceLastPublishSuccess() > staleLimit) {
            return new Check("mqtt", false, "no broker ack for " + s.msSinceLastPublishSuccess()
                    + "ms while still publishing (failures=" + s.consecutivePublishFailures() + ")");
        }
        return new Check("mqtt", true, "connected, failures=" + s.consecutivePublishFailures());
    }

    private List<Check> checkModbus(boolean inGrace) {
        List<Check> checks = new ArrayList<>();
        long stallLimit = healthConfig.modbusPollStallLimit().toMillis();
        long staleLimit = healthConfig.modbusPollStaleLimit().toMillis();
        for (AlfenModbusClient client : alfenModbus.getClients()) {
            String name = "modbus:" + client.getName();
            long stall = client.pollStallMs();
            long sinceSuccess = client.msSinceLastSuccessfulPoll();

            if (stall > stallLimit) {
                checks.add(new Check(name, false, "poll iteration stuck for " + stall + "ms (deadlock?)"));
                continue;
            }
            if (inGrace) {
                checks.add(new Check(name, true, "startup grace"));
                continue;
            }
            if (!client.isPolling()) {
                checks.add(new Check(name, false, "poll loop never started"));
                continue;
            }
            if (sinceSuccess < 0 || sinceSuccess > staleLimit) {
                checks.add(new Check(name, false, "no successful poll"
                        + (sinceSuccess < 0 ? " ever" : " for " + sinceSuccess + "ms")));
                continue;
            }
            checks.add(new Check(name, true, "last ok " + sinceSuccess + "ms ago"));
        }
        return checks;
    }
}
