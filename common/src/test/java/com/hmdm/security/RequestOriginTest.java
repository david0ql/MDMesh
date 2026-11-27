package com.hmdm.security;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RequestOriginTest {

    @Test public void behind_our_own_proxy_the_forwarded_client_is_the_origin() {
        assertEquals("190.0.0.7", RequestOrigin.resolve("172.18.0.5", null, "190.0.0.7, 172.18.0.2"));
        assertEquals("181.1.1.1", RequestOrigin.resolve("127.0.0.1", "181.1.1.1", "190.0.0.7"));
    }

    @Test public void a_direct_caller_cannot_forge_its_address_with_headers() {
        assertEquals("200.5.5.5", RequestOrigin.resolve("200.5.5.5", "1.1.1.1", "2.2.2.2"));
    }

    @Test public void without_headers_the_peer_is_the_origin() {
        assertEquals("172.18.0.5", RequestOrigin.resolve("172.18.0.5", null, " "));
        assertNull(RequestOrigin.resolve(null, null, null));
    }

    @Test public void the_thread_value_is_set_and_cleared() {
        RequestOrigin.set("10.1.1.1");
        assertEquals("10.1.1.1", RequestOrigin.ip());
        RequestOrigin.clear();
        assertNull(RequestOrigin.ip());
    }
}
