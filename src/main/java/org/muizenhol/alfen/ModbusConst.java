package org.muizenhol.alfen;


import org.muizenhol.homeassistant.Discovery;

import java.util.List;

/**
 * <a href="https://alfen.com/media/1047/download?attachment">Alfen modbus datasheet</a>
 */
public class ModbusConst {
    public enum DataType {
        STRING,
        SIGNED16,
        UNSIGNED16,
        UNSIGNED32,
        UNSIGNED64,
        FLOAT32,
        FLOAT64
    }

    public record Group(String name, int startOffset, int size, List<Item> items) {
    }

    public record Item(String name, int start, int size, DataType type, DiscoveryInfo discoveryInfo) {
        public Item(String name, int start, int size, DataType type) {
            this(name, start, size, type, false);
        }

        public Item(String name, int start, int size, DataType type, boolean expose) {
            this(name, start, size, type, null);
        }

        public record DiscoveryInfo(Discovery.Component.DeviceClass deviceClass,
                                    Discovery.Component.StateClass stateClass, String unit, int precision) {
        }

        public static final DiscoveryInfo POWER_WATT = new DiscoveryInfo(Discovery.Component.DeviceClass.POWER, Discovery.Component.StateClass.MEASUREMENT, "W", 0);
        public static final DiscoveryInfo VOLTAGE = new DiscoveryInfo(Discovery.Component.DeviceClass.VOLTAGE, Discovery.Component.StateClass.MEASUREMENT, "V", 0);
        public static final DiscoveryInfo CURRENT_2 = new DiscoveryInfo(Discovery.Component.DeviceClass.CURRENT, Discovery.Component.StateClass.MEASUREMENT, "A", 2);
        public static final DiscoveryInfo FREQUENCY_2 = new DiscoveryInfo(Discovery.Component.DeviceClass.FREQUENCY, Discovery.Component.StateClass.MEASUREMENT, "Hz", 2);
        public static final DiscoveryInfo ENERGY = new DiscoveryInfo(Discovery.Component.DeviceClass.ENERGY, Discovery.Component.StateClass.TOTAL_INCREASING, "Wh", 0);
    }

    public static final int ADDR_GENERIC = 200;
    public static final int ID_NR_OF_SOCKETS = 1105;
    public static final int ID_STATION_SERIAL_NUMBER = 157;
    public static final int ID_SOCKET_MAX_CURRENT = 1210;
    public static final int ID_NUM_PHASES = 1215;
    public static final int ID_REAL_POWER_SUM = 344;

    public enum StartOffset {
        PRODUCT_IDENTIFICATION(100),
        SOCKET_MEASUREMENT(300),
        STATION_STATUS(1100),
        SOCKET_STATUS(1200);

        public final int offset;

        StartOffset(int offset) {
            this.offset = offset;
        }
    }

    /**
     * Generic data. To be read on ID 200
     */
    public static final Group PRODUCT_IDENTIFICATION =
            new Group("product_identification", StartOffset.PRODUCT_IDENTIFICATION.offset, 79, List.of(
                    new Item("Name", 100, 17, DataType.STRING),
                    new Item("Manufacturer", 117, 5, DataType.STRING),
                    new Item("Modbus table version", 122, 1, DataType.SIGNED16),
                    new Item("Firmware version", 123, 17, DataType.STRING),
                    new Item("Platform type", 140, 17, DataType.STRING),
                    new Item("Station serial number", ID_STATION_SERIAL_NUMBER, 11, DataType.STRING),
                    new Item("Date year", 168, 1, DataType.SIGNED16),
                    new Item("Date month", 169, 1, DataType.SIGNED16),
                    new Item("Date day", 170, 1, DataType.SIGNED16),
                    new Item("Time hour", 171, 1, DataType.SIGNED16),
                    new Item("Time minute", 172, 1, DataType.SIGNED16),
                    new Item("Time second", 173, 1, DataType.SIGNED16),
                    new Item("Uptime", 174, 4, DataType.UNSIGNED64),
                    new Item("Time zone", 178, 1, DataType.SIGNED16)
            ));
    /**
     * Generic data. To be read on ID 200
     */
    public static final Group STATION_STATUS =
            new Group("station_status", StartOffset.STATION_STATUS.offset, 6, List.of(
                    new Item("Station Active Max Current", 1100, 2, DataType.FLOAT32),
                    new Item("Temperature", 1102, 2, DataType.FLOAT32),
                    new Item("OCPP state", 1104, 1, DataType.UNSIGNED16),
                    new Item("Nr of sockets", ID_NR_OF_SOCKETS, 1, DataType.UNSIGNED16)
            ));

    public static final Item ITEM_REAL_POWER_SUM = new Item("Real Power Sum", ID_REAL_POWER_SUM, 2, DataType.FLOAT32, Item.POWER_WATT);
    /**
     * Socket specific data. To be read once per socket
     */
    public static final Group SOCKET_MEASUREMENT =
            new Group("socket_measurement", StartOffset.SOCKET_MEASUREMENT.offset, 125, List.of(
                    new Item("Meter state", 300, 1, DataType.SIGNED16),
                    new Item("Meter last value timestamp", 301, 4, DataType.UNSIGNED64),
                    new Item("Meter type", 305, 1, DataType.UNSIGNED16),
                    new Item("Voltage Phase V(L1-N)", 306, 2, DataType.FLOAT32, Item.VOLTAGE),
                    new Item("Voltage Phase V(L2-N)", 308, 2, DataType.FLOAT32, Item.VOLTAGE),
                    new Item("Voltage Phase V(L3-N)", 310, 2, DataType.FLOAT32, Item.VOLTAGE),
                    new Item("Voltage Phase V(L1-L2)", 312, 2, DataType.FLOAT32),
                    new Item("Voltage Phase V(L2-L3)", 314, 2, DataType.FLOAT32),
                    new Item("Voltage Phase V(L3-L1)", 316, 2, DataType.FLOAT32),
                    new Item("Current N", 318, 2, DataType.FLOAT32),
                    new Item("Current Phase L1", 320, 2, DataType.FLOAT32, Item.CURRENT_2),
                    new Item("Current Phase L2", 322, 2, DataType.FLOAT32, Item.CURRENT_2),
                    new Item("Current Phase L3", 324, 2, DataType.FLOAT32, Item.CURRENT_2),
                    new Item("Current Sum", 326, 2, DataType.FLOAT32),
                    new Item("Power Factor Phase L1", 328, 2, DataType.FLOAT32),
                    new Item("Power Factor Phase L2", 330, 2, DataType.FLOAT32),
                    new Item("Power Factor Phase L3", 332, 2, DataType.FLOAT32),
                    new Item("Power Factor Sum", 334, 2, DataType.FLOAT32),
                    new Item("Frequency", 336, 2, DataType.FLOAT32, Item.FREQUENCY_2),
                    new Item("Real Power Phase L1", 338, 2, DataType.FLOAT32),
                    new Item("Real Power Phase L2", 340, 2, DataType.FLOAT32),
                    new Item("Real Power Phase L3", 342, 2, DataType.FLOAT32),
                    ITEM_REAL_POWER_SUM,
                    new Item("Apparent Power Phase L1", 346, 2, DataType.FLOAT32),
                    new Item("Apparent Power Phase L2", 348, 2, DataType.FLOAT32),
                    new Item("Apparent Power Phase L3", 350, 2, DataType.FLOAT32),
                    new Item("Apparent Power Sum", 352, 2, DataType.FLOAT32),
                    new Item("Reactive Power Phase L1", 354, 2, DataType.FLOAT32),
                    new Item("Reactive Power Phase L2", 356, 2, DataType.FLOAT32),
                    new Item("Reactive Power Phase L3", 358, 2, DataType.FLOAT32),
                    new Item("Reactive Power Sum", 360, 2, DataType.FLOAT32),
                    new Item("Real Energy Delivered Phase L1", 362, 4, DataType.FLOAT64),
                    new Item("Real Energy Delivered Phase L2", 366, 4, DataType.FLOAT64),
                    new Item("Real Energy Delivered Phase L3", 370, 4, DataType.FLOAT64),
                    new Item("Real Energy Delivered Sum", 374, 4, DataType.FLOAT64, Item.ENERGY),
                    new Item("Real Energy Consumed Phase L1", 378, 4, DataType.FLOAT64),
                    new Item("Real Energy Consumed Phase L2", 382, 4, DataType.FLOAT64),
                    new Item("Real Energy Consumed Phase L3", 386, 4, DataType.FLOAT64),
                    new Item("Real Energy Consumed Sum", 390, 4, DataType.FLOAT64),
                    new Item("Apparent Energy Phase L1", 394, 4, DataType.FLOAT64),
                    new Item("Apparent Energy Phase L2", 398, 4, DataType.FLOAT64),
                    new Item("Apparent Energy Phase L3", 402, 4, DataType.FLOAT64),
                    new Item("Apparent Energy Sum", 406, 4, DataType.FLOAT64),
                    new Item("Reactive Energy Phase L1", 410, 4, DataType.FLOAT64),
                    new Item("Reactive Energy Phase L2", 414, 4, DataType.FLOAT64),
                    new Item("Reactive Energy Phase L3", 418, 4, DataType.FLOAT64)
                    //new Item("Reactive Energy Sum", 422, 4, DataType.FLOAT64) //seems problematic reading out this value
            ));

    public static final Item ITEM_MAX_CURRENT = new Item("Modbus Slave Max Current", ID_SOCKET_MAX_CURRENT, 2, DataType.FLOAT32, Item.CURRENT_2);
    public static final Item ITEM_NUM_PHASES = new Item("Charge using 1 or 3 phases", ID_NUM_PHASES, 1, DataType.UNSIGNED16);
    /**
     * Socket specific data. To be read once per socket
     */
    public static final Group STATUS =
            new Group("status", StartOffset.SOCKET_STATUS.offset, 16, List.of(
                    new Item("Availability", 1200, 1, DataType.UNSIGNED16),
                    new Item("Mode 3 state", 1201, 5, DataType.STRING),
                    new Item("Actual Applied Max Current", 1206, 2, DataType.FLOAT32, Item.CURRENT_2),
                    new Item("Modbus Slave Max Current valid time", 1208, 2, DataType.UNSIGNED32),
                    ITEM_MAX_CURRENT,
                    new Item("Active Load Balancing Safe Current", 1212, 2, DataType.FLOAT32),
                    new Item("Modbus Slave received setpoint accounted for", 1214, 1, DataType.UNSIGNED16),
                    ITEM_NUM_PHASES
            ));

}
