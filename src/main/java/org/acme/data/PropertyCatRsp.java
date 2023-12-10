package org.acme.data;

import java.util.List;

public record PropertyCatRsp(int version, List<Property> properties, int offset, int total) {
    public record Property(String id, int access, int type, int len, String cat, String value) {
    }
}
