package org.muizenhol.alfen;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MockAlfenModbusTestResource.class)
public class AlfenModbusTest {

    @Inject
    AlfenModbus alfenModbus;

    @Test
    public void test1() {
        alfenModbus.handleWrite("ikke", 1, "mykey", "payload");
    }
}
