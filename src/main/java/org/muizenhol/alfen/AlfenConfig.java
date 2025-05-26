package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import jakarta.ws.rs.DefaultValue;

import java.util.Optional;
import java.util.Set;

@ConfigMapping(prefix = "alfen")
public interface AlfenConfig {
    Set<Device> devices();

    interface Device {
        String endpoint();
        @DefaultValue("502")
        int port();

        Optional<String> username();

        Optional<String> password();

        String name();
        DeviceType type();
    }
    enum DeviceType {
        MODBUS,
        HTTP
    }

}
