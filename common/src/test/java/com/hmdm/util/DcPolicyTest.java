package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class DcPolicyTest {

    @Test
    public void normalize_stores_clean_json_or_null() {
        assertNull(DcPolicy.normalize(null));
        assertNull(DcPolicy.normalize("{}"));
        assertNull("nothing valid left", DcPolicy.normalize("{\"kioskRoles\":[\"x\"],\"trackingMinutes\":99999}"));
        assertEquals("{\"kioskRoles\":[\"phone\",\"contacts\"],\"trackingMinutes\":5}",
                DcPolicy.normalize("{\"kioskRoles\":[\"contacts\",\"phone\",\"phone\"],\"trackingMinutes\":5,\"extra\":1}"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalize_refuses_non_objects() {
        DcPolicy.normalize("[1,2]");
    }

    @Test
    public void parse_never_throws() {
        assertTrue(DcPolicy.parse("nope").isEmpty());
        assertTrue(DcPolicy.parse(null).isEmpty());
    }

    @Test
    public void package_names_and_urls_are_validated() {
        assertTrue(DcPolicy.isPackageName("com.android.chrome"));
        assertFalse(DcPolicy.isPackageName("chrome"));
        assertFalse(DcPolicy.isPackageName("com.x; rm -rf"));
        DcPolicy p = DcPolicy.parse("{\"browser\":{\"mode\":\"BLOCKLIST\",\"block\":[\"a.\\u0000com\",\"b.com\",\"b.com\"]}}");
        assertEquals("blocklist", p.getBrowser().getMode());
        assertEquals(java.util.Collections.singletonList("b.com"), p.getBrowser().getBlock());
    }
}
