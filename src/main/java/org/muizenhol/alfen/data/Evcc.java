package org.muizenhol.alfen.data;

public final class Evcc {
    private Evcc() {
    }

    public record Charger(boolean enabled, String status, Float power) {
    }

    public record setValue(String key, String value) {
    }

    ;
}
