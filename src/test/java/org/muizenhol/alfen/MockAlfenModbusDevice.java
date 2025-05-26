package org.muizenhol.alfen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

public class MockAlfenModbusDevice implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public MockAlfenModbusDevice(Vertx vertx, int port) {
        try {

            NetServer netServer = vertx.createNetServer();
            LOG.info("Creating mock alfen modbus device: {}", netServer);
            netServer.connectHandler(this::connectHandler);
            netServer.exceptionHandler(t -> {
                LOG.warn("Error connecting to alfen modbus device", t);
            });
            NetServer result = netServer.listen(port, "0.0.0.0").result();

//            netServer.listene
            LOG.info("Creating mock alfen modbus device OK: {}", result);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String bufferToHex(Buffer buffer) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buffer.length(); i++) {
            sb.append(String.format("%02X", buffer.getByte(i)));
            if (i < buffer.length() - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private void connectHandler(NetSocket netSocket) {
        LOG.warn("Alfen modbus device connected: {}", netSocket.remoteAddress());
        netSocket.handler(buffer -> {
            LOG.info("handling: {}", bufferToHex(buffer));
        });
        netSocket.closeHandler(v -> {
            LOG.warn("Alfen modbus device close: {}", netSocket.remoteAddress());
        });
        netSocket.exceptionHandler(t -> {
            LOG.warn("Error connecting to alfen modbus device", t);
        });
        netSocket.drainHandler(v -> {
            LOG.warn("Alfen modbus device drain");
        });
        netSocket.endHandler(v -> {
            LOG.warn("Alfen modbus device end: {}", netSocket.remoteAddress());
        });
    }


    @Override
    public void close() throws Exception {
        LOG.info("Closing mock alfen modbus device");
    }

}
