package com.hmdm.util;

import com.hmdm.persistence.domain.AuditEntry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/** The audit trail's hash chain: an edited row, or one removed from the middle, is detected. */
public class AuditHashTest {

    private static List<AuditEntry> chain(int n) {
        List<AuditEntry> rows = new ArrayList<>();
        String prev = null;
        for (int i = 1; i <= n; i++) {
            AuditEntry e = new AuditEntry();
            e.id = (long) i;
            e.createdAt = 1_700_000_000_000L + i;
            e.customerId = 1;
            e.userId = 7;
            e.login = "tecnico";
            e.category = "comandos";
            e.action = "device.command:device.lock";
            e.label = "Comando al dispositivo: bloqueo de pantalla";
            e.targetType = "device";
            e.targetId = "dev-" + i;
            e.result = "ok";
            e.detail = "{\"solicitud\":{\"type\":\"device.lock\"}}";
            e.prevHash = prev;
            e.hash = AuditHash.of(e);
            prev = e.hash;
            rows.add(e);
        }
        return rows;
    }

    @Test
    public void anIntactChainVerifies() {
        assertNull(AuditHash.verify(chain(5), null, true));
        assertEquals(64, chain(1).get(0).hash.length());
    }

    @Test
    public void anEditedRowIsDetected() {
        List<AuditEntry> rows = chain(5);
        rows.get(2).result = "denied";
        assertEquals(Long.valueOf(3), AuditHash.verify(rows, null, true));
        rows = chain(5);
        rows.get(3).detail = "{}";
        assertEquals(Long.valueOf(4), AuditHash.verify(rows, null, true));
        rows = chain(5);
        rows.get(1).login = "otro";
        assertEquals(Long.valueOf(2), AuditHash.verify(rows, null, true));
    }

    @Test
    public void aRowRemovedFromTheMiddleIsDetected() {
        List<AuditEntry> rows = chain(5);
        rows.remove(2);
        assertEquals(Long.valueOf(4), AuditHash.verify(rows, null, true));
    }

    @Test
    public void theOldestKeptRowIsNotBlamedForThePurge() {
        // The retention purge removes the oldest rows: the first row kept points at a row that is gone.
        List<AuditEntry> rows = chain(6).subList(3, 6);
        assertNull(AuditHash.verify(rows, null, false));
        // ...but in the middle of a run the link is checked.
        assertEquals(Long.valueOf(4), AuditHash.verify(rows, "x", true));
    }

    @Test
    public void fieldsCannotBeShiftedIntoEachOther() {
        AuditEntry a = new AuditEntry();
        a.login = "ab";
        a.roleName = "c";
        AuditEntry b = new AuditEntry();
        b.login = "a";
        b.roleName = "bc";
        assertNotEquals(AuditHash.of(a), AuditHash.of(b));
        AuditEntry c = new AuditEntry();
        c.login = "";
        AuditEntry d = new AuditEntry();
        assertNotEquals(AuditHash.of(c), AuditHash.of(d));
    }
}
