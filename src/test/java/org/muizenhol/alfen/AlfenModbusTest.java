package org.muizenhol.alfen;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

@QuarkusTest
@QuarkusTestResource(value = ModbusTestResource.class, restrictToAnnotatedClass = true)
public class AlfenModbusTest {

    @Inject
    AlfenModbus alfenModbus;

    @ModbusTestResource.InjectMock
    MockAlfenModbusDevice mockAlfenDevice;

    @BeforeEach
    public void beforeEach() {
        alfenModbus.start();
    }

    @AfterEach
    public void afterEach() {
        alfenModbus.stop();
    }

    @Test
    public void test1() throws Exception {
//        HttpClient.newHttpClient().send(
//                HttpRequest.newBuilder().GET().uri(URI.create("http://127.0.0.1:5502/")).build(),
//                responseInfo -> {
//                    responseInfo.statusCode();
//                    return null;
//                }
//        );
        //alfenModbus.handleWrite("ikke", 1, "mykey", "payload");
    }
}
