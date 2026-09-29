package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeviceNamingTest {
    @Test
    public void serial_by_default_with_fallbacks() {
        assertEquals("ZY22H4", DeviceNaming.name(null, "ZY22H4", "3569", "moto g31"));
        assertEquals("3569", DeviceNaming.name("serial", "unknown", "3569", "moto g31"));
        assertEquals("3569", DeviceNaming.name("IMEI", "ZY22H4", "3569", null));
        assertEquals("ZY22H4", DeviceNaming.name("imei", "ZY22H4", null, null));
        assertEquals("moto g31 · ZY22H4", DeviceNaming.name("model-serial", "ZY22H4", "3569", "moto g31"));
        assertNull(DeviceNaming.name("none", "ZY22H4", "3569", "moto g31"));
        assertNull(DeviceNaming.name("serial", "", null, "moto"));
        assertEquals("unknown rule = serial", "ZY22H4", DeviceNaming.name("teleport", "ZY22H4", null, null));
    }
}
