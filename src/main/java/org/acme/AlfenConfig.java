package org.acme;

import io.smallrye.config.ConfigMapping;

import java.util.Set;

@ConfigMapping(prefix = "alfen")
public interface AlfenConfig {
    Set<Device> devices();

    interface Device {
        String endpoint();

        String username();

        String password();

        String name();
    }
}
