package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlayStoreLookupTest {
    @Test
    public void package_from_name_or_link() {
        assertEquals("com.whatsapp", PlayStoreLookup.packageOf(" com.whatsapp "));
        assertEquals("com.whatsapp", PlayStoreLookup.packageOf("https://play.google.com/store/apps/details?id=com.whatsapp&hl=es_CO"));
        assertEquals("co.amovil.preventa", PlayStoreLookup.packageOf("https://play.google.com/store/apps/details?hl=es&id=co.amovil.preventa"));
        assertNull(PlayStoreLookup.packageOf("whatsapp"));
        assertNull(PlayStoreLookup.packageOf("https://example.com/?x=1"));
    }

    @Test
    public void parses_title_and_icon() {
        String html = "<html><head><meta property=\"og:title\" content=\"WhatsApp Messenger - Aplicaciones en Google Play\">"
                + "<meta property=\"og:image\" content=\"https://play-lh.googleusercontent.com/abc=w526\"></head></html>";
        PlayStoreLookup.Result r = PlayStoreLookup.parse("com.whatsapp", html);
        assertEquals("WhatsApp Messenger", r.name);
        assertEquals("https://play-lh.googleusercontent.com/abc=w526", r.icon);
        assertNull(PlayStoreLookup.parse("x.y", "<html></html>").name);
        assertNull("non-https icons are dropped",
                PlayStoreLookup.parse("x.y", "<meta property=\"og:image\" content=\"javascript:alert(1)\">").icon);
    }
}
