/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import javax.inject.Named;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.hmdm.notification.rest.NotificationResource;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.rest.filter.PublicIPFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DownloadFilesServlet extends HttpServlet {
    private final ApplicationDAO applicationDAO;
    private final String filesDirectory;
    private final File baseDirectory;
    private final PublicIPFilter publicIPFilter;

    private boolean secureEnrollment;
    private String hashSecret;

    private static final String HEADER_ENROLLMENT_SIGNATURE = "X-Request-Signature";
    private static final String CONTENT_TYPE_APK = "application/vnd.android.package-archive";

    private static final Logger log = LoggerFactory.getLogger(DownloadFilesServlet.class);

    @Inject
    public DownloadFilesServlet(ApplicationDAO applicationDAO,
                                PublicIPFilter publicIPFilter,
                                @Named("files.directory") String filesDirectory,
                                @Named("secure.enrollment") boolean secureEnrollment,
                                @Named("hash.secret") String hashSecret) {
        this.applicationDAO = applicationDAO;
        this.filesDirectory = filesDirectory;
        this.baseDirectory = new File(filesDirectory);
        this.publicIPFilter = publicIPFilter;
        this.secureEnrollment = secureEnrollment;
        this.hashSecret = hashSecret;
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }

    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = URLDecoder.decode(req.getRequestURI(), "UTF8");
        int index = path.indexOf("/files/", 0) + "/files/".length();
        path = path.substring(index);

        if (!publicIPFilter.match(req)) {
            log.warn("Request blocked by IP: " + req.getRemoteAddr());
            resp.sendError(403);
            return;
        }

        if (secureEnrollment && !applicationDAO.isMainApp("%" + path)) {
            String signature = req.getHeader(HEADER_ENROLLMENT_SIGNATURE);
            if (signature == null) {
                log.warn("No signature for file request " + req.getRequestURL().toString());
                resp.sendError(403);
                return;
            }
            try {
                String goodSignature = CryptoUtil.getSHA1String(hashSecret + path);
                if (!signature.equalsIgnoreCase(goodSignature)) {
                    log.warn("Wrong signature for file request " + path + ": " + signature + " Should be: " + goodSignature);
                    resp.sendError(403);
                    return;
                }
            } catch (Exception e) {
            }
        }

        File file = new File(String.format("%s/%s", this.filesDirectory, path));
        // Containment (defence in depth — Tomcat and Caddy also normalise the URI): never serve a file whose canonical
        // path is outside the files directory.
        String base = this.baseDirectory.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(base)) {
            log.warn("Blocked file request outside the files directory: {}", path);
            resp.sendError(404);
            return;
        }
        if (file.exists() && file.isFile()) {

            long modifiedSince = req.getDateHeader("If-Modified-Since");
            if (modifiedSince != -1 && modifiedSince > file.lastModified()) {
                // Client can use cached images
                resp.setStatus(304);
                return;
            }

            String range = req.getHeader("Range");
            if (range != null && range.startsWith("bytes=")) {
                sendPartialContent(range.substring(6), file, resp);
                return;
            }

            // Cross XSS vulnerability fix: prevent opening a potentially malicious file having the Headwind MDM domain
            resp.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName().replaceAll("[\"\\\\\r\n]", "_") + "\"");
            try (InputStream input = new FileInputStream(file);
                 ServletOutputStream outputStream = resp.getOutputStream()) {
                long length = file.length();
                if (length <= 2147483647L) {
                    resp.setContentLength((int)length);
                } else {
                    resp.addHeader("Content-Length", Long.toString(length));
                }
                if (file.getAbsolutePath().endsWith(".apk")) {
                    resp.setContentType(CONTENT_TYPE_APK);
                }

                IOUtils.copy(input, outputStream);
                outputStream.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Not found: " + file.getAbsolutePath());
            resp.sendError(404);
        }

    }

    /**
     * The byte range a {@code Range: bytes=<spec>} header asks for in a file of [length] bytes, as
     * {first, last} (both inclusive); null when it cannot be satisfied (starts at or past the end: HTTP 416).
     * Understands "a-b", "a-" and "-n" (the last n bytes); only the first range of a list is served.
     *
     * @throws IllegalArgumentException when the spec is not a byte range at all
     */
    static long[] parseRange(String spec, long length) {
        String first = spec.split(",")[0].trim();
        int dash = first.indexOf('-');
        if (dash < 0) throw new IllegalArgumentException("not a range: " + spec);
        String from = first.substring(0, dash).trim();
        String to = first.substring(dash + 1).trim();
        long start;
        long end;
        if (from.isEmpty()) {
            long suffix = Long.parseLong(to);
            if (suffix <= 0 || length == 0) return null;
            start = Math.max(0, length - suffix);
            end = length - 1;
        } else {
            start = Long.parseLong(from);
            end = to.isEmpty() ? length - 1 : Math.min(Long.parseLong(to), length - 1);
        }
        if (start < 0 || start >= length || end < start) return null;
        return new long[]{start, end};
    }

    /**
     * Serves part of a file (HTTP 206): how the agent resumes a big APK whose download was cut. A range that starts
     * at or past the end answers 416 with the file's size — the agent reads that as "I already have it all".
     */
    private void sendPartialContent(String rangeStr, File file, HttpServletResponse resp) {
        long length = file.length();
        long[] range;
        try {
            range = parseRange(rangeStr, length);
        } catch (RuntimeException e) {
            range = new long[]{0, Math.max(0, length - 1)}; // not a byte range: the whole file
        }
        try {
            if (range == null || length == 0) {
                resp.setStatus(416);
                resp.setHeader("Content-Range", "bytes */" + length);
                return;
            }
            long start = range[0];
            long end = range[1];
            long contentLength = end - start + 1;
            resp.setStatus(206);
            // Same guard as a full download: a hosted file is never rendered inline from this origin.
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName().replaceAll("[\"\\\\\r\n]", "_") + "\"");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Accept-Ranges", "bytes");
            if (resp.getContentType() == null) {
                resp.setContentType("application/octet-stream");
            }
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            resp.setHeader("Content-Length", Long.toString(contentLength));
            if (file.getAbsolutePath().endsWith(".apk")) {
                resp.setContentType(CONTENT_TYPE_APK);
            }
            try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r");
                 ServletOutputStream outputStream = resp.getOutputStream()) {
                input.seek(start);
                byte[] buf = new byte[64 * 1024];
                long left = contentLength;
                while (left > 0) {
                    int n = input.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    outputStream.write(buf, 0, n);
                    left -= n;
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            // Usually the phone dropping the connection mid-download; it resumes with another Range request.
            log.debug("Partial download of {} interrupted: {}", file.getName(), e.toString());
        }
    }
}
