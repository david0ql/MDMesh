package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.WifiCertificateMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.WifiCertificateStore;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.DcWifi;
import com.hmdm.util.WifiCerts;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Certificates of the enterprise Wi-Fi networks ("Certificados Wi-Fi"): the CA that signs the RADIUS server's
 * certificate (PEM or DER) and, for EAP-TLS, the client certificate (PKCS#12 with its password). A policy's network
 * refers to them by id; the phones receive them inside their policy document and the Device Owner installs them.
 *
 * <p>What is uploaded is parsed, sealed (AES-256-GCM, key outside the database) and never returned: the API only
 * answers who the certificate belongs to, who issued it and when it expires. Reading needs the "policies" permission;
 * uploading and deleting also need "create policies", because a certificate is shared by every policy of the account.</p>
 */
@Singleton
@Path("/private/wifi-certificates")
@Api(tags = {"Wi-Fi certificates"})
public class WifiCertificateResource {
    private static final Logger logger = LoggerFactory.getLogger(WifiCertificateResource.class);
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** "Expires soon" in the console. */
    static final int WARN_DAYS = 30;

    private WifiCertificateMapper mapper;
    private WifiCertificateStore store;

    /** A constructor required by Swagger. */
    public WifiCertificateResource() {
    }

    @Inject
    public WifiCertificateResource(WifiCertificateMapper mapper, WifiCertificateStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    public static class Upload {
        public String name;
        /** CA or CLIENT. */
        public String kind;
        /** The file, base64. */
        public String data;
        /** The PKCS#12 password (CLIENT). */
        public String password;
    }

    @ApiOperation(value = "List Wi-Fi certificates", notes = "Metadata only: subject, issuer, validity, fingerprint and the policies using each.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<Integer> c = viewer();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        Map<Integer, List<String>> usedBy = usedBy(c.get());
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.list(c.get())) out.add(view(row, usedBy, now));
        return Response.OK(out);
    }

    @ApiOperation(value = "Upload a Wi-Fi certificate", notes = "CA: PEM or DER. CLIENT: PKCS#12 and its password.")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(Upload u) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        String name = u == null || u.name == null ? "" : u.name.trim();
        if (name.isEmpty() || name.length() > 120) return Response.ERROR("Ponle un nombre al certificado (hasta 120 caracteres).");
        boolean client = u.kind != null && WifiCerts.CLIENT.equalsIgnoreCase(u.kind.trim());
        WifiCerts.Material m;
        try {
            byte[] data = u.data == null ? new byte[0] : Base64.getMimeDecoder().decode(u.data);
            m = client ? WifiCerts.parseClient(data, u.password) : WifiCerts.parseCa(data);
        } catch (IllegalArgumentException e) {
            // Never the content or the password: only which check failed.
            logger.info("Wi-Fi certificate upload refused ({}) for customer {}", e.getMessage(), c.get());
            return Response.ERROR(message(e.getMessage()));
        }
        long now = System.currentTimeMillis();
        if (m.notAfter() < now) return Response.ERROR("El certificado ya venció; sube uno vigente.");
        Map<String, Object> row = new HashMap<>();
        row.put("customerId", c.get());
        row.put("name", name);
        row.put("kind", m.kind);
        row.put("subject", cut(m.first().getSubjectX500Principal().getName()));
        row.put("issuer", cut(m.first().getIssuerX500Principal().getName()));
        row.put("serial", m.first().getSerialNumber().toString(16));
        row.put("fingerprint", m.fingerprint());
        row.put("notBefore", m.notBefore());
        row.put("notAfter", m.notAfter());
        row.put("certCount", m.certs.size());
        row.put("sealed", store.seal(m));
        row.put("createdAt", now);
        row.put("createdBy", SecurityContext.get().getCurrentUserName());
        mapper.insert(row);
        logger.info("Wi-Fi certificate #{} ({}, fingerprint {}) uploaded by {}", row.get("id"), m.kind, m.fingerprint(),
                SecurityContext.get().getCurrentUserName());
        row.remove("sealed");
        return Response.OK(view(lower(row), new HashMap<>(), now));
    }

    @ApiOperation(value = "Delete a Wi-Fi certificate", notes = "Refused while a policy's network uses it.")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        List<String> used = usedBy(c.get()).get(id);
        if (used != null && !used.isEmpty()) {
            return Response.ERROR("Lo usa " + (used.size() == 1 ? "la política «" + used.get(0) + "»" : used.size() + " políticas")
                    + ": quítalo de la red Wi‑Fi antes de eliminarlo.");
        }
        if (mapper.delete(c.get(), id) == 0) return Response.ERROR("El certificado no existe.");
        store.forget(c.get(), id);
        logger.info("Wi-Fi certificate #{} deleted by {}", id, SecurityContext.get().getCurrentUserName());
        return Response.OK();
    }

    /** Certificate id -> names of the policies whose networks refer to it. */
    private Map<Integer, List<String>> usedBy(int customerId) {
        Map<Integer, List<String>> out = new HashMap<>();
        for (Map<String, Object> p : mapper.policiesWithCertificates(customerId)) {
            Object raw = p.get("dcpolicy");
            for (Integer id : DcWifi.certificateIds(raw == null ? null : String.valueOf(raw))) {
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(String.valueOf(p.get("name")));
            }
        }
        return out;
    }

    private static Map<String, Object> view(Map<String, Object> row, Map<Integer, List<String>> usedBy, long now) {
        Map<String, Object> v = new LinkedHashMap<>();
        int id = ((Number) row.get("id")).intValue();
        long notAfter = row.get("notafter") == null ? 0 : ((Number) row.get("notafter")).longValue();
        v.put("id", id);
        v.put("name", row.get("name"));
        v.put("kind", row.get("kind"));
        v.put("subject", row.get("subject"));
        v.put("issuer", row.get("issuer"));
        v.put("serial", row.get("serial"));
        v.put("fingerprint", row.get("fingerprint"));
        v.put("notBefore", row.get("notbefore"));
        v.put("notAfter", notAfter);
        v.put("certCount", row.get("certcount"));
        v.put("createdAt", row.get("createdat"));
        v.put("createdBy", row.get("createdby"));
        v.put("daysLeft", (int) Math.floorDiv(notAfter - now, DAY_MS));
        v.put("status", notAfter < now ? "expired" : notAfter - now < WARN_DAYS * DAY_MS ? "expiring" : "valid");
        v.put("usedBy", usedBy.getOrDefault(id, new ArrayList<>()));
        return v;
    }

    private static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) out.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
        return out;
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }

    static String message(String key) {
        if ("error.wifi.cert.size".equals(key)) return "El archivo está vacío o pesa más de 64 KB.";
        if ("error.wifi.cert.password".equals(key)) return "No se pudo abrir el archivo: revisa que sea un PKCS#12 (.p12/.pfx) y su contraseña.";
        if ("error.wifi.cert.nokey".equals(key)) return "El PKCS#12 no trae una clave privada con su certificado.";
        return "No se pudo leer el certificado: debe ser un X.509 en PEM o DER.";
    }

    private static Optional<Integer> viewer() {
        if (!SecurityContext.get().hasPermission("configurations")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }

    private static Optional<Integer> editor() {
        if (!SecurityContext.get().hasPermission("configurations") || !SecurityContext.get().hasPermission("add_config")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }
}
