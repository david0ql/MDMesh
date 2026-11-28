package com.hmdm.rest.resource.support;

import com.hmdm.persistence.mapper.WifiCertificateMapper;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredWifi;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.WifiCerts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The sealed Wi-Fi certificates: seals an upload, and puts the certificates a policy's enterprise networks refer to
 * into a phone's desired-state document ({@link #inline}). The encryption key lives in the server's base directory
 * (next to its RSA key), never in the database.
 */
@Singleton
public class WifiCertificateStore {
    private static final Logger logger = LoggerFactory.getLogger(WifiCertificateStore.class);

    private final WifiCertificateMapper mapper;
    private final String baseDirectory;
    private WifiCerts.Vault vault;
    /** Opened certificates by "customer:id". A stored certificate never changes (it is uploaded or deleted, not edited). */
    private final java.util.concurrent.ConcurrentHashMap<String, WifiCerts.Material> opened = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    public WifiCertificateStore(WifiCertificateMapper mapper, @Named("base.directory") String baseDirectory) {
        this.mapper = mapper;
        this.baseDirectory = baseDirectory;
    }

    private synchronized WifiCerts.Vault vault() {
        if (vault == null) {
            try {
                vault = WifiCerts.Vault.inDirectory(new File(baseDirectory));
            } catch (Exception e) {
                throw new IllegalStateException("the Wi-Fi certificate key is not available in base.directory", e);
            }
        }
        return vault;
    }

    /** A deleted certificate must not be served from memory. */
    public void forget(int customerId, int id) {
        opened.remove(customerId + ":" + id);
    }

    public String seal(WifiCerts.Material m) {
        return vault().seal(m);
    }

    /** The stored certificate, or null when it does not exist, is of another account, or cannot be opened. */
    public WifiCerts.Material open(int customerId, int id) {
        try {
            String key = customerId + ":" + id;
            WifiCerts.Material cached = opened.get(key);
            if (cached != null) return cached;
            Map<String, Object> row = mapper.sealed(customerId, id);
            if (row == null) return null;
            WifiCerts.Material m = vault().open(String.valueOf(row.get("sealed")));
            if (opened.size() < 500) opened.put(key, m);
            return m;
        } catch (Exception e) {
            logger.warn("Wi-Fi certificate #{} cannot be opened: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Replace the certificate ids of the document's enterprise networks by the certificates themselves, and recompute
     * the revision (so replacing a certificate re-applies the policy). A certificate that is gone leaves the network
     * without it: the phone then reports the network as failed instead of joining it unverified.
     *
     * @return the same document
     */
    public DesiredConfig inline(DesiredConfig doc, Integer customerId) {
        if (doc == null || doc.getWifiAdvanced() == null || customerId == null) return doc;
        boolean changed = false;
        for (DesiredWifi w : doc.getWifiAdvanced()) {
            DesiredWifi.Eap e = w.getEap();
            if (e == null) continue;
            if (e.getCaCertIds() != null) {
                List<String> cas = new ArrayList<String>();
                for (Integer id : e.getCaCertIds()) {
                    WifiCerts.Material m = open(customerId, id);
                    if (m != null && WifiCerts.CA.equals(m.kind)) cas.addAll(m.certsBase64());
                }
                e.setCaCerts(cas.isEmpty() ? null : cas);
                changed = true;
            }
            if (e.getClientCertId() != null) {
                WifiCerts.Material m = open(customerId, e.getClientCertId());
                if (m != null && WifiCerts.CLIENT.equals(m.kind)) {
                    e.setClientKey(m.keyBase64());
                    e.setClientKeyAlgorithm(m.keyAlgorithm);
                    e.setClientChain(m.certsBase64());
                }
                changed = true;
            }
        }
        if (changed) doc.setRevision(DesiredConfigBuilder.revision(doc));
        return doc;
    }
}
