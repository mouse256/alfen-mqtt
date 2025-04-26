package org.muizenhol.homeassistant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;

public record Discovery(
        @JsonProperty("device")
        Device device,

        @JsonProperty("origin")
        Origin origin,

        @JsonProperty("state_topic")
        String stateTopic,

        @JsonProperty("components")
        Map<String, Component> components) {
    public record Device(
            String identifiers,
            String manufacturer,
            String model,
            String name) {
    }

    public record Origin(String name) {
    }

    public record Component(
            @JsonProperty("name")
            String name,

            @JsonProperty("unique_id")
            String uniqueId,

            @JsonProperty("platform")
            Platform platform,

            @JsonProperty("device_class")
            DeviceClass deviceClass,

            @JsonProperty("state_class")
            StateClass stateClass,

            @JsonProperty("unit_of_measurement")
            String unitOfMeasurement,

            @JsonProperty("suggested_display_precision")
            int suggestedDisplayPrecision,

            @JsonProperty("value_template")
            String valueTemplate
    ) {
        /**
         * See <a href="https://developers.home-assistant.io/docs/core/entity/sensor/">docs</a>
         */
        public enum DeviceClass {
            POWER,
            CURRENT,
            FREQUENCY,
            ENERGY,
            VOLTAGE;

            @JsonValue
            public String getName() {
                return name().toLowerCase();
            }
        }

        /**
         * See <a href="https://developers.home-assistant.io/docs/core/entity/sensor/">docs</a>
         */
        public enum StateClass {
            MEASUREMENT,
            TOTAL_INCREASING;

            @JsonValue
            public String getName() {
                return name().toLowerCase();
            }
        }

        public enum Platform {
            SENSOR;

            @JsonValue
            public String getName() {
                return name().toLowerCase();
            }
        }
    }
}
