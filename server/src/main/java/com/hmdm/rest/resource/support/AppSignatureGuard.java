package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.util.ApkSigner;
import com.hmdm.util.FileUtil;
import com.hmdm.util.SignatureCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the signing certificate of an APK being added to the library with the one of the versions of the same
 * package already there (read from their hosted files). See {@link SignatureCheck}.
 */
@Singleton
public class AppSignatureGuard {
    private static final Logger logger = LoggerFactory.getLogger(AppSignatureGuard.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationDAO applicationDAO;
    private final String filesDirectory;
    private final String baseUrl;

    @Inject
    public AppSignatureGuard(ApplicationDAO applicationDAO,
                             @Named("files.directory") String filesDirectory,
                             @Named("base.url") String baseUrl) {
        this.applicationDAO = applicationDAO;
        this.filesDirectory = filesDirectory;
        this.baseUrl = baseUrl;
    }

    /**
     * The verdict for [newApk] as a new version of [pkg] in the current user's library. Never throws: anything that
     * cannot be read is "unknown".
     *
     * @param exceptVersionId a version to leave out of the comparison (the one being added), or null
     */
    public SignatureCheck.Result check(String pkg, File newApk, Customer customer, Integer exceptVersionId) {
        ApkSigner.Signer fresh = newApk == null ? null : ApkSigner.of(newApk);
        String freshSha = fresh == null ? null : fresh.sha256;
        boolean hasPrevious = false;
        try {
            List<ApplicationVersion> versions = new ArrayList<>();
            for (Application app : applicationDAO.findByPackageId(pkg)) {
                if (app.getId() == null) continue;
                versions.addAll(applicationDAO.getApplicationVersions(app.getId()));
            }
            // Newest first: the version the phones most likely run.
            versions.sort((a, b) -> Integer.compare(b.getVersionCode(), a.getVersionCode()));
            for (ApplicationVersion v : versions) {
                if (exceptVersionId != null && exceptVersionId.equals(v.getId())) continue;
                hasPrevious = true;
                File hosted = hostedFile(v, customer);
                // The very file being checked is not a "previous" version (the same upload committed twice).
                if (hosted == null || (newApk != null && hosted.getAbsoluteFile().equals(newApk.getAbsoluteFile()))) continue;
                ApkSigner.Signer s = ApkSigner.of(hosted);
                if (s != null) {
                    return SignatureCheck.compare(true, s.sha256, versionLabel(v), freshSha);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not compare the signature of {} with its library versions: {}", pkg, e.toString());
            return SignatureCheck.compare(true, null, null, freshSha);
        }
        return SignatureCheck.compare(hasPrevious, null, null, freshSha);
    }

    /** The verdict for a version about to be added from its hosted URL(s); "unknown" when the file is not ours. */
    public SignatureCheck.Result checkNewVersion(ApplicationVersion version, Customer customer) {
        try {
            if (version == null || version.getApplicationId() == null) return SignatureCheck.compare(false, null, null, null);
            Application app = applicationDAO.findById(version.getApplicationId());
            if (app == null || app.getPkg() == null) return SignatureCheck.compare(false, null, null, null);
            return check(app.getPkg(), hostedFile(version, customer), customer, version.getId());
        } catch (Exception e) {
            logger.warn("Could not check the signature of a new application version: {}", e.toString());
            return SignatureCheck.compare(true, null, null, null);
        }
    }

    private static String versionLabel(ApplicationVersion v) {
        String name = v.getVersion() == null || v.getVersion().trim().isEmpty() ? null : v.getVersion().trim();
        return name == null ? "versionCode " + v.getVersionCode() : name + " (versionCode " + v.getVersionCode() + ")";
    }

    /** The version's APK on this server's disk (a bundle: its base part, else any part), or null when it is elsewhere. */
    File hostedFile(ApplicationVersion v, Customer customer) {
        List<String> urls = new ArrayList<>();
        if (v.getParts() != null && !v.getParts().trim().isEmpty()) {
            try {
                String first = null;
                for (JsonNode p : JSON.readTree(v.getParts())) {
                    String u = p.path("url").asText("");
                    if (u.isEmpty()) continue;
                    if ("base".equals(p.path("split").asText(""))) urls.add(0, u);
                    else if (first == null) first = u;
                }
                if (first != null) urls.add(first);
            } catch (Exception ignored) {
                // malformed parts: fall back to the single URLs
            }
        }
        for (String u : new String[]{v.getUrl(), v.getUrlArm64(), v.getUrlArmeabi()}) {
            if (u != null && !u.trim().isEmpty()) urls.add(u.trim());
        }
        for (String u : urls) {
            String rel = FileUtil.translateURLToLocalFilePath(customer, u, baseUrl);
            if (rel == null || !FileUtil.isSafePath(rel)) continue;
            String dir = customer.getFilesDir() == null || customer.getFilesDir().isEmpty()
                    ? filesDirectory : filesDirectory + File.separator + customer.getFilesDir();
            File f = new File(dir, rel);
            if (f.isFile()) return f;
        }
        return null;
    }
}
