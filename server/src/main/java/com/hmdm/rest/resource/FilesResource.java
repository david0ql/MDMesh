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

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.*;
import com.hmdm.rest.json.view.FileView;
import com.hmdm.util.APKFileAnalyzer;
import com.hmdm.util.StringUtil;
import org.apache.commons.io.FileUtils;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.ResponseHeader;
import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.FileExistsException;
import com.hmdm.util.FileUtil;

@Api(tags = {"Files"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/web-ui-files")
public class FilesResource {
    private static final Logger logger = LoggerFactory.getLogger(FilesResource.class);

    private String filesDirectory;
    private String baseUrl;
    private File baseDirectory;
    private CustomerDAO customerDAO;
    private UnsecureDAO unsecureDAO;
    private ApplicationDAO applicationDAO;
    private ConfigurationDAO configurationDAO;
    private UploadedFileDAO uploadedFileDAO;
    private APKFileAnalyzer apkFileAnalyzer;
    private ConfigurationFileDAO configurationFileDAO;
    private IconDAO iconDAO;
    private PushService pushService;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public FilesResource() {
    }

    @Inject
    public FilesResource(@Named("files.directory") String filesDirectory,
                         @Named("base.url") String baseUrl,
                         CustomerDAO customerDAO,
                         UnsecureDAO unsecureDAO,
                         ApplicationDAO applicationDAO,
                         ConfigurationDAO configurationDAO,
                         UploadedFileDAO uploadedFileDAO,
                         APKFileAnalyzer apkFileAnalyzer,
                         ConfigurationFileDAO configurationFileDAO,
                         IconDAO iconDAO,
                         PushService pushService) {
        this.filesDirectory = filesDirectory;
        this.baseDirectory = new File(filesDirectory);
        this.customerDAO = customerDAO;
        this.unsecureDAO = unsecureDAO;
        this.applicationDAO = applicationDAO;
        this.configurationDAO = configurationDAO;
        this.uploadedFileDAO = uploadedFileDAO;
        this.configurationFileDAO = configurationFileDAO;
        this.iconDAO = iconDAO;
        this.pushService = pushService;
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
        this.apkFileAnalyzer = apkFileAnalyzer;

        this.baseUrl = baseUrl;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get all files",
            notes = "Gets the list of all available files",
            response = FileView.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllFiles() {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.generateFilesList((String)null));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Remove a file",
            notes = "Removes the file from the MDM server"
    )
    @POST
    @Path("/remove")
    public Response removeFile(FileView file) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to remove a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (!FileUtil.isSafePath(file.getFilePath())) {
            logger.error("Attempt to remove a file with unsafe path! path: " + file.getFilePath());
            return Response.PERMISSION_DENIED();
        }

        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());

            // Check if file is not used
            if (this.configurationFileDAO.isFileUsed(file.getId())) {
                return Response.FILE_USED();
            } else if (this.iconDAO.isFileUsed(file.getId())) {
                return Response.FILE_USED();
            }

            if (!file.isExternal()) {
                java.nio.file.Path filePath;
                if (customer.getFilesDir() == null || customer.getFilesDir().isEmpty()) {
                    filePath = Paths.get(this.filesDirectory, file.getFilePath());
                } else {
                    filePath = Paths.get(this.filesDirectory, customer.getFilesDir(), file.getFilePath());
                }

                try {
                    logger.info("File {} is removed by user {}", file.getFilePath(), SecurityContext.get().getCurrentUserName());
                    uploadedFileDAO.remove(file.getId());
                    if (filePath.toFile().exists() &&
                            uploadedFileDAO.getByPath(customer.getId(), file.getFilePath()) == null) {
                        Files.delete(filePath);
                    }
                    return Response.OK();
                } catch (IOException e) {
                    e.printStackTrace();
                    return Response.ERROR("error.file.deletion");
                }
            } else {
                logger.info("External file {} ({}) is removed by user {}",
                        file.getDescription(), file.getUrl(), SecurityContext.get().getCurrentUserName());
                uploadedFileDAO.remove(file.getId());
                return Response.OK();
            }
        }).orElse(Response.PERMISSION_DENIED());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Complete file upload",
            notes = "Commits the file upload to MDM server. Returns the uploaded file data",
            response = FileView.class
    )
    @POST
    @Path("/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateFile(UploadedFile uploadedFile) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to update a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (uploadedFile.getId() == null) {
            if (!uploadedFile.isExternal()) {
                return createFileInternal(uploadedFile);
            } else {
                return createExternalFileInternal(uploadedFile);
            }
        } else {
            return updateFileInternal(uploadedFile);
        }
    }

    private Response createFileInternal(UploadedFile uploadedFile) {
        if (uploadedFile.getFilePath() == null) {
            uploadedFile.setFilePath("");
        }
        String tmpdir = System.getProperty("java.io.tmpdir");
        if (!FileUtil.isSafePath(uploadedFile.getFilePath()) ||
            !uploadedFile.getTmpPath().startsWith(tmpdir)) {
            logger.error("Attempt to create a file with unsafe path: " + uploadedFile.getFilePath() +
                    " tmp path: " + uploadedFile.getTmpPath());
            return Response.PERMISSION_DENIED();
        }

        String fname = uploadedFile.getFileName();
        // Empty fname means using the default name from tmp path (processed by moveFile)
        if (fname.equals("")) {
            fname = FileUtil.getNameFromTmpPath(uploadedFile.getTmpPath());
            while (fname.startsWith("/")) {
                fname = fname.substring(1);
            }
            uploadedFile.setFilePath(fname);
        }
        final String fileName = fname;

        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());
            try {
                File movedFile = FileUtil.moveFile(customer, filesDirectory, uploadedFile.getSubdir(), uploadedFile.getTmpPath(), fileName);
                if (movedFile != null) {
                    List<FileView> result = new LinkedList<>();
                    handleFile(movedFile, result, null, customer);

                    BasicFileAttributes attrs = Files.readAttributes(movedFile.toPath(), BasicFileAttributes.class);
                    uploadedFile.setUploadTime(attrs.creationTime().toMillis());
                    uploadedFile.setCustomerId(customer.getId());
                    uploadedFileDAO.insert(uploadedFile);
                    uploadedFile.setUrl(uploadedFile.getUrl(baseUrl, customer));

                    return Response.OK(uploadedFile);
                } else {
                    return Response.ERROR("error.file.save");
                }
            } catch (FileExistsException e) {
                logger.warn("File {} already exists", uploadedFile.getFilePath());
                return Response.FILE_EXISTS();
            } catch (IOException e) {
                logger.warn("While creating file {}: {}", uploadedFile.getFilePath(), e.getMessage());
                e.printStackTrace();
                return Response.INTERNAL_ERROR();
            }
        }).orElse(Response.PERMISSION_DENIED());
    }

    private Response createExternalFileInternal(UploadedFile uploadedFile) {
        if (uploadedFile.getExternalUrl() == null || uploadedFile.getExternalUrl().trim().equals("")) {
            logger.warn("While creating external file: empty URL");
            return Response.ERROR();
        }
        return SecurityContext.get().getCurrentUser().map(u -> {
            if (uploadedFile.getFilePath() == null) {
                // It was previously set as NOT NULL
                uploadedFile.setFilePath("");
            }
            Customer customer = customerDAO.findById(u.getCustomerId());
            uploadedFile.setCustomerId(customer.getId());
            uploadedFileDAO.insert(uploadedFile);
            uploadedFile.setUrl(uploadedFile.getUrl(baseUrl, customer));

            return Response.OK(uploadedFile);
        }).orElse(Response.PERMISSION_DENIED());
    }

    private Response updateFileInternal(UploadedFile uploadedFile) {
        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());
            if (!uploadedFile.isExternal()) {
                UploadedFile dbFile = uploadedFileDAO.getById(uploadedFile.getId());
                if (!StringUtil.isEmpty(uploadedFile.getTmpPath())) {
                    // Renew the file content - remove the old file and overwrite with the new content
                    FileUtil.deleteFile(customer, filesDirectory, dbFile.getFilePath());
                    File movedFile = FileUtil.moveFile(customer, filesDirectory, dbFile.getSubdir(),
                            uploadedFile.getTmpPath(), dbFile.getFileName());
                    if (movedFile == null) {
                        return Response.ERROR("error.file.save");
                    }
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(movedFile.toPath(), BasicFileAttributes.class);
                        uploadedFile.setUploadTime(attrs.lastModifiedTime().toMillis());
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Response.INTERNAL_ERROR();
                    }
                }
                if (!dbFile.getFilePath().equals(uploadedFile.getFilePath())) {
                    if (!FileUtil.isSafePath(uploadedFile.getFilePath())) {
                        logger.error("Attempt to move a file to unsafe path: " + uploadedFile.getFilePath());
                        return Response.PERMISSION_DENIED();
                    }
                    String srcPath = String.format("%s/%s/%s", filesDirectory, customer.getFilesDir(), dbFile.getFilePath());
                    File movedFile = FileUtil.moveFile(customer, filesDirectory, "",
                            srcPath, uploadedFile.getFilePath());
                    if (movedFile == null) {
                        logger.error("Failed to move a file to path: " + uploadedFile.getFilePath());
                        return Response.ERROR("error.file.save");
                    }
                }
            }
            uploadedFileDAO.update(uploadedFile);
            return Response.OK();
        }).orElse(Response.PERMISSION_DENIED());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Search files",
            notes = "Search files meeting the specified filter value",
            response = FileView.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFilesByName(@PathParam("value") @ApiParam("A filter value") String value) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.generateFilesList(value));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get applications",
            notes = "Gets the list of applications using the file",
            response = Application.class,
            responseContainer = "List"
    )
    @GET
    @Path("/apps/{url}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationsForFile(@PathParam("url") @ApiParam("An URL referencing the file") String url) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            String decodedUrl = URLDecoder.decode(url, "UTF-8");
            return Response.OK(this.applicationDAO.getAllApplicationsByUrl(decodedUrl));
        } catch (Exception e) {
            logger.error("Unexpected error when getting the list of applications by URL", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @GET
    @Path("/limit")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStorageLimit() {
        LimitResponse lr = new LimitResponse();
        if (!unsecureDAO.isSingleCustomer()) {
            // Check the disk size in multi-tenant mode
            Customer currentCustomer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            if (!currentCustomer.isMaster() && currentCustomer.getSizeLimit() > 0) {
                File userDir = new File(this.filesDirectory, currentCustomer.getFilesDir());
                long userDirSize = 0;
                if (userDir.exists()) {
                    userDirSize = FileUtils.sizeOfDirectory(userDir);
                }
                lr.setSizeUsed((int) (userDirSize / 1048576l));
                lr.setSizeLimit(currentCustomer.getSizeLimit());
            }
        }
        return Response.OK(lr);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get file configurations",
            notes = "Gets the list of configurations using requested file",
            response = ApplicationConfigurationLink.class,
            responseContainer = "List"
    )
    @GET
    @Path("/configurations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFileConfigurations(@PathParam("id") @ApiParam("File ID") Integer id) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to get file configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.uploadedFileDAO.getFileConfigurations(id));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Update file configurations",
            notes = "Updates the list of configurations using requested file"
    )
    @POST
    @Path("/configurations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateFileConfigurations(LinkConfigurationsToFileRequest request) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to update file configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            User user = SecurityContext.get().getCurrentUser().get();
            if (!user.isAllConfigAvailable()) {
                // Remove all configurations unavailable to user
                request.getConfigurations().removeIf(c ->
                        user.getConfigurations()
                                .stream()
                                .filter(uc -> uc.getId() == c.getConfigurationId()).findFirst() == null);
            }
            // Avoid access to objects of another customer
            request.getConfigurations().removeIf(c -> {
                // findById will raise a SecurityException if attempting to access an object of another customer
                // So actually this code is a bit redundant, but it guards access to own objects anyway
                UploadedFile file = uploadedFileDAO.getById(c.getFileId());
                Configuration configuration = configurationDAO.getConfigurationById(c.getConfigurationId());
                return file.getCustomerId() != user.getCustomerId() ||
                        configuration.getCustomerId() != user.getCustomerId();
            });
            logger.info("File configurations updated by user " + SecurityContext.get().getCurrentUserName());
            this.uploadedFileDAO.updateFileConfigurations(request.getConfigurations());

            for (FileConfigurationLink configurationLink : request.getConfigurations()) {
                if (configurationLink.isNotify()) {
                    this.pushService.notifyDevicesOnUpdate(configurationLink.getConfigurationId());
                }
            }

            return Response.OK();
        } catch (Exception e) {
            logger.error("Unexpected error when updating file configurations", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload raw file",
            notes = "Uploads the raw file to server (without attempt to parse APK). Returns a path to uploaded file",
            response = FileUploadResult.class
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/raw")
    public Response uploadFilesRaw(@FormDataParam("file") InputStream uploadedInputStream,
                                @ApiParam("A file to upload") @FormDataParam("file") FormDataContentDisposition fileDetail) throws Exception {
        return uploadFilesInternal(uploadedInputStream, fileDetail, false);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload file or application",
            notes = "Uploads the file or application to server. Returns a path to uploaded file",
            response = FileUploadResult.class
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFiles(@FormDataParam("file") InputStream uploadedInputStream,
                                @ApiParam("A file to upload") @FormDataParam("file") FormDataContentDisposition fileDetail) throws Exception {
        return uploadFilesInternal(uploadedInputStream, fileDetail, true);
    }

    // =================================================================================================================
    private Response uploadFilesInternal(InputStream uploadedInputStream,
                                         FormDataContentDisposition fileDetail,
                                         boolean parseFile) throws Exception {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to upload a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            // For some reason, the browser sends the file name in ISO_8859_1, so we use a workaround to convert
            // it to UTF_8 and enable non-ASCII characters
            // https://stackoverflow.com/questions/50582435/jersey-filename-encoded
            String fileName = new String(fileDetail.getFileName().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            String adjustedFileName = FileUtil.adjustFileName(fileName);
            File uploadFile = FileUtil.createTempFile(adjustedFileName);
            FileUtil.writeToFile(uploadedInputStream, uploadFile.getAbsolutePath());

            FileUploadResult result = new FileUploadResult();
            result.setName(fileName);

            if (!unsecureDAO.isSingleCustomer()) {
                // Check the disk size in multi-tenant mode
                Customer currentCustomer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
                if (!currentCustomer.isMaster() && currentCustomer.getSizeLimit() > 0) {
                    File userDir = new File(this.filesDirectory, currentCustomer.getFilesDir());
                    long userDirSize = 0;
                    long uploadFileSize = uploadFile.length();
                    if (userDir.exists()) {
                        userDirSize = FileUtils.sizeOfDirectory(userDir);
                    }
                    long totalSizeMb = (userDirSize + uploadFileSize) / 1048576l;
                    if (totalSizeMb > currentCustomer.getSizeLimit()) {
                        uploadFile.delete();
                        logger.warn("Storage limit exceeded for customer {}: {}/{}", currentCustomer.getName(),
                                totalSizeMb, currentCustomer.getSizeLimit());
                        return Response.ERROR("error.size.limit.exceeded",
                                "" + totalSizeMb + " / " + currentCustomer.getSizeLimit());
                    }
                }
            }

            result.setServerPath(uploadFile.getAbsolutePath());

            if (parseFile && fileName.endsWith("apk")) {
                final APKFileDetails apkFileDetails;
                apkFileDetails = this.apkFileAnalyzer.analyzeFile(uploadFile.getAbsolutePath());
                result.setFileDetails(apkFileDetails);

                // The same version code under a new version name is accepted: some apps (Amovil's) ship each build
                // with the same code and only change the name. The agent installs it again by that name.
                ApplicationVersion version;
                version = this.applicationDAO.findApplicationVersion(apkFileDetails.getPkg(), apkFileDetails.getVersion());
                if (StringUtil.isEmpty(apkFileDetails.getArch())){
                    if (version != null) {
                        result.setExists(true);
                    }
                } else if (apkFileDetails.getArch().equals(Application.ARCH_ARMEABI)) {
                    // If version for arm64 is already uploaded, set the complete flag
                    result.setComplete(version != null && !StringUtil.isEmpty(version.getUrlArm64()));
                    // Check if version is already uploaded
                    result.setExists(version != null && (!version.isSplit() || !StringUtil.isEmpty(version.getUrlArmeabi())));
                } else if (apkFileDetails.getArch().equals(Application.ARCH_ARM64)) {
                    result.setComplete(version != null && !StringUtil.isEmpty(version.getUrlArmeabi()));
                    result.setExists(version != null && (!version.isSplit() || !StringUtil.isEmpty(version.getUrlArm64())));
                }

                final List<Application> dbAppsByPkg = this.applicationDAO.findByPackageId(apkFileDetails.getPkg());
                if (!dbAppsByPkg.isEmpty()) {
                    final Application dbApp = dbAppsByPkg.get(0);
                    final Application dbAppCopy = new Application();
                    dbAppCopy.setId(dbApp.getId());
                    dbAppCopy.setShowIcon(dbApp.getShowIcon());
                    dbAppCopy.setUseKiosk(dbApp.getUseKiosk());
                    dbAppCopy.setRunAfterInstall(dbApp.isRunAfterInstall());
                    dbAppCopy.setRunAtBoot(dbApp.isRunAtBoot());
                    dbAppCopy.setSystem(dbApp.isSystem());
                    dbAppCopy.setName(dbApp.getName());
                    dbAppCopy.setPkg(dbApp.getPkg());

                    result.setApplication(dbAppCopy);
                }
            }


            return Response.OK(result);
        } catch (Exception e) {
            logger.error("Unexpected error when handling file upload", e);
            return Response.ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload a split-APK bundle",
            notes = "Unpacks a .xapk/.apks/zip bundle into individual APKs, hosts each, and returns the "
                    + "deployable part list for a split app.install. A plain .apk yields a single part."
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/bundle")
    public Response uploadBundle(@FormDataParam("file") InputStream uploadedInputStream,
                                 @FormDataParam("file") FormDataContentDisposition fileDetail) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to upload a bundle by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        File bundleTmp = null;
        try {
            String fileName = new String(fileDetail.getFileName().getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8);
            bundleTmp = FileUtil.createTempFile(FileUtil.adjustFileName(fileName));
            FileUtil.writeToFile(uploadedInputStream, bundleTmp.getAbsolutePath());
            Customer customer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            return Response.OK(importBundle(bundleTmp, fileName, fileName.toLowerCase().endsWith(".apk"), customer));
        } catch (BundleException e) {
            return Response.ERROR(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error unpacking bundle", e);
            return Response.ERROR("error.bundle.unpack");
        } finally {
            if (bundleTmp != null) bundleTmp.delete();
        }
    }

    // --- Fetch an app's public APK by package name (background job: the download can take minutes) -----------------

    /** One download in progress or just finished; kept an hour. */
    static final class FetchJob {
        final String packageName;
        final int customerId;
        final long startedAt = System.currentTimeMillis();
        volatile String state = "running"; // running | done | error
        volatile long bytes;
        volatile long total = -1;
        volatile java.util.Map<String, Object> result;
        volatile String error;

        FetchJob(String packageName, int customerId) {
            this.packageName = packageName;
            this.customerId = customerId;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, FetchJob> FETCH_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService FETCH_POOL = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "apk-fetch");
        t.setDaemon(true);
        return t;
    });

    public static class FetchBody {
        public String packageName;
    }

    @ApiOperation(value = "Fetch an app's public APK", notes = "Starts a background download of the app's APK (or split "
            + "bundle) by package name and hosts it; poll GET /fetch/{job} for progress and the same result as /bundle.")
    @POST
    @Path("/fetch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response fetchApk(FetchBody body) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            return Response.PERMISSION_DENIED();
        }
        final String pkg = body == null || body.packageName == null ? "" : body.packageName.trim();
        if (!com.hmdm.util.ApkFetcher.PACKAGE.matcher(pkg).matches()) {
            return Response.ERROR("El nombre de paquete no es válido.");
        }
        final Customer customer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
        final int customerId = customer.getId();
        long now = System.currentTimeMillis();
        FETCH_JOBS.values().removeIf(j -> now - j.startedAt > 3600_000L);
        for (java.util.Map.Entry<String, FetchJob> e : FETCH_JOBS.entrySet()) {
            FetchJob j = e.getValue();
            if (j.customerId == customerId && j.packageName.equals(pkg) && "running".equals(j.state)) {
                return Response.OK(java.util.Collections.singletonMap("job", e.getKey()));
            }
        }
        final String id = java.util.UUID.randomUUID().toString();
        final FetchJob job = new FetchJob(pkg, customerId);
        FETCH_JOBS.put(id, job);
        FETCH_POOL.submit(() -> {
            File tmp = null;
            try {
                tmp = FileUtil.createTempFile("apkfetch");
                com.hmdm.util.ApkFetcher.Fetched f = com.hmdm.util.ApkFetcher.fetch(pkg, tmp, (done, total) -> { job.bytes = done; job.total = total; });
                java.util.Map<String, Object> out = importBundle(f.file, pkg + (f.plainApk ? ".apk" : ".xapk"), f.plainApk, customer);
                if (!pkg.equals(out.get("packageName"))) {
                    throw new IllegalStateException("El archivo descargado es de otro paquete (" + out.get("packageName") + ").");
                }
                job.result = out;
                job.state = "done";
                logger.info("APK of {} fetched: version {} ({}), signer {}", pkg, out.get("version"), out.get("versionCode"), out.get("signerSha256"));
            } catch (Exception e) {
                job.error = e instanceof BundleException ? "El archivo descargado no es un APK utilizable."
                        : e.getMessage() == null ? "No se pudo descargar." : e.getMessage();
                job.state = "error";
                logger.warn("Fetching the APK of {} failed: {}", pkg, e.toString());
            } finally {
                if (tmp != null) tmp.delete();
            }
        });
        return Response.OK(java.util.Collections.singletonMap("job", id));
    }

    @ApiOperation(value = "Progress of an APK fetch")
    @GET
    @Path("/fetch/{job}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response fetchStatus(@PathParam("job") String id) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            return Response.PERMISSION_DENIED();
        }
        FetchJob j = FETCH_JOBS.get(id);
        if (j == null || j.customerId != SecurityContext.get().getCurrentCustomerId().orElse(-1)) {
            return Response.ERROR("La descarga ya no existe.");
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("state", j.state);
        out.put("packageName", j.packageName);
        out.put("bytes", j.bytes);
        out.put("total", j.total);
        out.put("result", j.result);
        out.put("error", j.error);
        return Response.OK(out);
    }

    /** A bundle that cannot be used; the message is the error key (or text) for the console. */
    static final class BundleException extends Exception {
        BundleException(String message) { super(message); }
    }

    /** An APK taken out of a bundle, with the name it had inside it (e.g. {@code config.arm64_v8a.apk}). */
    private static final class ExtractedPart {
        final File file;
        final String entryName;

        ExtractedPart(File file, String entryName) {
            this.file = file;
            this.entryName = entryName;
        }
    }

    /**
     * Hosts every APK of a bundle (.xapk/.apks/zip, or one plain .apk) and describes it for a split app.install:
     * packageName, version, versionCode, the parts (url, sha256, name, split = the part's name inside the bundle,
     * which the agent uses to pick the splits that fit each phone) and who signed it.
     */
    java.util.Map<String, Object> importBundle(File bundle, String fileName, boolean plainApk, Customer customer) throws Exception {
        List<ExtractedPart> partTmps = new LinkedList<>();
        try {
            if (plainApk) {
                File copy = FileUtil.createTempFile("bundlepart");
                java.nio.file.Files.copy(bundle.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                partTmps.add(new ExtractedPart(copy, "base.apk"));
            } else {
                partTmps = extractApkParts(bundle);
                if (partTmps.isEmpty()) {
                    throw new BundleException("error.bundle.noApks"); // encrypted .apkm, or not an APK bundle
                }
            }

            // Package + version come from any part (splits share them); prefer one that parses cleanly.
            APKFileDetails meta = null;
            File base = null;
            for (ExtractedPart part : partTmps) {
                try {
                    APKFileDetails d = apkFileAnalyzer.analyzeFile(part.file.getAbsolutePath());
                    if (d != null && d.getPkg() != null && d.getVersionCode() != 0) { meta = d; base = part.file; break; }
                    if (meta == null) { meta = d; base = part.file; }
                } catch (Exception ignored) { /* a config split may not parse standalone — try the next */ }
            }
            if (meta == null || meta.getPkg() == null) {
                throw new BundleException("error.bundle.unreadable");
            }
            com.hmdm.util.ApkSigner.Signer signer = base == null ? null : com.hmdm.util.ApkSigner.of(base);

            List<java.util.Map<String, Object>> parts = new LinkedList<>();
            for (ExtractedPart part : partTmps) {
                // Content-addressed name: identical bytes → identical name → re-uploading the same
                // bundle reuses the already-hosted part instead of colliding (moveFile throws on an
                // existing name). A different build hashes differently and never clobbers.
                String sha256 = sha256Hex(part.file);
                String partName = String.format("%s-%s.apk", meta.getPkg(), sha256);
                String url = hostFile(part.file, partName, customer);
                java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
                p.put("url", url);
                p.put("sha256", sha256);
                p.put("name", partName);
                p.put("split", part.entryName.toLowerCase().replaceFirst("\\.apk$", ""));
                parts.add(p);
            }
            partTmps.clear(); // moved/reused into the files area — nothing left to clean up

            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("name", fileName);
            out.put("packageName", meta.getPkg());
            // Some apps name their version through a resource the analyzer cannot read: fall back to the version code
            // (the library needs a version text, and it orders versions by it).
            out.put("version", meta.getVersion() == null || meta.getVersion().trim().isEmpty()
                    ? String.valueOf(meta.getVersionCode()) : meta.getVersion());
            out.put("versionCode", meta.getVersionCode());
            out.put("parts", parts);
            if (signer != null) {
                out.put("signerSha256", signer.sha256);
                out.put("signerSubject", signer.subject);
                out.put("publisher", com.hmdm.util.ApkSigner.publisher(signer.sha256));
            }
            logger.info("Bundle {} unpacked: {} parts for {} ({})", fileName, parts.size(), meta.getPkg(),
                    meta.getVersionCode());
            return out;
        } finally {
            for (ExtractedPart f : partTmps) f.file.delete();
        }
    }

    /** Extract every {@code *.apk} entry of a zip container to temp files. If a {@code universal.apk}
     *  is present (bundletool .apks), return ONLY it — it's a self-contained single install. */
    private List<ExtractedPart> extractApkParts(File zip) throws IOException {
        List<ExtractedPart> parts = new LinkedList<>();
        ExtractedPart universal = null;
        // ZipFile reads through the central directory: unlike a ZipInputStream it handles STORED entries written
        // with data descriptors, which store bundles (.xapk) use.
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String base = new File(entry.getName()).getName().toLowerCase();
                if (!base.endsWith(".apk")) continue;
                // Output name is generated (not the entry name), so a malicious entry path can't escape.
                File out = FileUtil.createTempFile("bundlepart");
                try (InputStream in = zf.getInputStream(entry);
                     java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) os.write(buf, 0, n);
                }
                if (base.equals("universal.apk")) universal = new ExtractedPart(out, "base.apk"); else parts.add(new ExtractedPart(out, base));
            }
        }
        if (universal != null) {
            for (ExtractedPart f : parts) f.file.delete(); // discard splits; the universal APK stands alone
            List<ExtractedPart> only = new LinkedList<>();
            only.add(universal);
            return only;
        }
        return parts;
    }

    /**
     * Host an extracted APK under [fileName] and return its public URL. Idempotent: when a file of
     * that name already exists (a re-upload of the same content-addressed part), reuse it rather
     * than throwing FileExistsException — the bytes are identical by construction.
     */
    private String hostFile(File apkTmp, String fileName, Customer customer) throws IOException {
        // Resolve the exact target path moveFile would use (no drift), so the existence check is reliable.
        File dest = FileUtil.resolveFile(customer, filesDirectory, null, fileName);
        File hosted;
        if (dest.exists()) {
            hosted = dest;          // same content-addressed part already served; reuse it
            apkTmp.delete();        // drop the redundant temp copy
        } else {
            hosted = FileUtil.moveFile(customer, filesDirectory, null, apkTmp.getAbsolutePath(), fileName);
            if (hosted == null) throw new IOException("failed to host " + fileName);
        }
        List<FileView> view = new LinkedList<>();
        handleFile(hosted, view, null, customer);
        if (view.isEmpty()) throw new IOException("hosted file produced no URL: " + fileName);
        return view.get(0).getUrl();
    }

    /** Lowercase hex SHA-256 — the format the agent's InstallManager verifies each part against. */
    private static String sha256Hex(File file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e); // never on a stock JRE
        }
    }

    // (DallyControl: the unauthenticated-path "Download a file" endpoint was removed — it built a file path straight
    // from the request with no containment check and nothing used it. Files are served by DownloadFilesServlet.)

    private List<FileView> generateFilesList(String value) {
        List<FileView> files = SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());

            List<UploadedFile> customerFiles = value != null ?
                    uploadedFileDAO.getAllByValue(value) :
                    uploadedFileDAO.getAll();
            List<FileView> result = customerFiles.stream()
                    .map(f -> {
                        FileView hFile = new FileView(f, this.baseUrl, this.filesDirectory, customer);
                        hFile.setUsedByConfigurations(this.configurationFileDAO.getUsingConfigurations(customer.getId(), hFile.getId()));
                        hFile.setUsedByIcons(this.iconDAO.getUsingIcons(customer.getId(), hFile.getId()));
                        return hFile;
                    })
                    .collect(Collectors.toList());
            return result;

        }).orElse(new LinkedList<>());

        return files;
    }

    private void handleFile(File file, List<FileView> result, String value, Customer customer) {
        final String customerFilesBaseDir = customer.getFilesDir();
        if (file != null && file.exists()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                File[] filesArray = files;
                int length = files.length;

                for(int i = 0; i < length; ++i) {
                    File fl = filesArray[i];
                    this.handleFile(fl, result, value, customer);
                }
            } else if (value == null || file.getName().contains(value)) {

                String path = file.getParentFile().getAbsolutePath().replace(this.filesDirectory.replace("/", File.separator), "");
                if (!path.endsWith(File.separator)) {
                    path += File.separator;
                }
                String url;
                if (customerFilesBaseDir != null && !customerFilesBaseDir.isEmpty()) {
                    path = path.substring((File.separator + customerFilesBaseDir + File.separator).length()); // Strip off the name of directory for customer files
                    url = String.format("%s/files/%s/%s", this.baseUrl, customerFilesBaseDir, path.replace(File.separator, "/") + file.getName());
                } else {
                    url = String.format("%s/files%s", this.baseUrl, path.replace(File.separator, "/") + file.getName());
                }

                final FileView fileObj = new FileView(path, file.getName(), url, file.length());

                fileObj.setUsedByConfigurations(this.configurationFileDAO.getUsingConfigurations(customer.getId(), fileObj.getId()));
                fileObj.setUsedByIcons(this.iconDAO.getUsingIcons(customer.getId(), fileObj.getId()));

                result.add(fileObj);
            }
        }

    }
}