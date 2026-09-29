package com.hmdm.rest.resource;

import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.APKFileAnalyzer;
import com.hmdm.rest.json.APKFileDetails;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The DallyControl agent this server hosts (files/dallycontrol-agent.apk) as an app.install spec — so the console can
 * update the agent on one device or many: the phone installs it over itself (Device Owner, same signing key) and checks
 * the sha256. Read once per file change.
 */
@Singleton
@Path("/private/agent-package")
@Api(tags = {"Agent package"})
public class AgentPackageResource {

    static final String FILE = "dallycontrol-agent.apk";

    private String filesDirectory;
    private String baseUrl;
    private APKFileAnalyzer analyzer;
    private Map<String, Object> cached;
    private long cachedStamp;

    /** A constructor required by Swagger. */
    public AgentPackageResource() {
    }

    @Inject
    public AgentPackageResource(@Named("files.directory") String filesDirectory, @Named("base.url") String baseUrl,
                                APKFileAnalyzer analyzer) {
        this.filesDirectory = filesDirectory;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.analyzer = analyzer;
    }

    @ApiOperation(value = "Hosted agent APK", notes = "url, packageName, versionCode, version, sha256")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized Response get() {
        if (!SecurityContext.get().getCurrentCustomerId().isPresent()) return Response.PERMISSION_DENIED();
        File f = new File(filesDirectory, FILE);
        if (!f.isFile()) return Response.ERROR("error.agent.package.missing");
        long stamp = f.lastModified() ^ f.length();
        if (cached == null || stamp != cachedStamp) {
            try {
                APKFileDetails d = analyzer.analyzeFile(f.getAbsolutePath());
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("url", baseUrl + "/files/" + FILE);
                p.put("packageName", d.getPkg());
                p.put("versionCode", d.getVersionCode());
                p.put("version", d.getVersion());
                p.put("sha256", sha256(f));
                cached = p;
                cachedStamp = stamp;
            } catch (Exception e) {
                return Response.ERROR("error.agent.package.unreadable");
            }
        }
        return Response.OK(cached);
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
