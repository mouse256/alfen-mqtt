package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;
import java.util.Set;

@ConfigMapping(prefix = "alfen")
public interface AlfenConfig {
    Set<Device> devices();

    interface Device {
        String endpoint();

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
