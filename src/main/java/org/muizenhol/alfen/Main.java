package org.muizenhol.alfen;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

@ApplicationScoped
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    AlfenModbus alfenModbus;

    @Inject
    AlfenController alfenController;

    @ConfigProperty(name = "auto.startup", defaultValue = "true")
    boolean autoStartup;

    public void onStart(@Observes StartupEvent startupEvent) {
        if (!autoStartup) {
            return;
        }
        LOG.info("Starting up");
        alfenModbus.start();
        alfenController.start();
    }

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        if (!autoStartup) {
            return;
        }
        LOG.info("Shutting down");
        alfenModbus.stop();
        alfenController.stop();
    }
}
