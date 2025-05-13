package org.muizenhol.alfen;


import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

public class AlfenModbusUnitTest {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Test
    public void writeFloatTest() {
        float testValue = 6;
        ModbusConst.Item item = ModbusConst.ITEM_MAX_CURRENT;
        ByteBuffer buf = ByteBuffer.allocate(item.size() * 2);
        buf.putFloat(testValue);
        byte[] values = buf.array();
        LOG.info("out: {}", values);
    }
}
