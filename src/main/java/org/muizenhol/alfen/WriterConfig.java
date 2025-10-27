package org.muizenhol.alfen;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "writer")
public interface WriterConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("PT5s")
    Duration interval();

    @WithDefault("6")
    int minPower();

}
