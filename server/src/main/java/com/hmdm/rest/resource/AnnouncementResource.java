package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.DeviceScopeRow;
import com.hmdm.persistence.mapper.AnnouncementMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.AnnouncementSender;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Announcements: a message (text plus an optional image or video) shown in the DallyControl app on the phones of some
 * folders, some devices or all of them. Mandatory ones open full screen until the person confirms; optional ones arrive
 * as a notification and stay in the app's inbox. Each device's receipt says when it arrived, was seen and confirmed.
 */
@Singleton
@Path("/private/announcements")
@Api(tags = {"Announcements"})
public class AnnouncementResource {

    private static final Logger logger = LoggerFactory.getLogger(AnnouncementResource.class);
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video");
    private static final int MAX_DEVICES = 10_000;

    private AnnouncementMapper mapper;
    private AgentCommandDAO commandDAO;
    private AnnouncementSender sender;

    /** A constructor required by Swagger. */
    public AnnouncementResource() {
    }

    @Inject
    public AnnouncementResource(AnnouncementMapper mapper, AgentCommandDAO commandDAO, AnnouncementSender sender) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        this.sender = sender;
    }

    public static class Body {
        public String title;
        public String body;
        public String mediaUrl;
        public String mediaType;
        public boolean mandatory;
        /** Stop delivering (and showing) after this many days; null = no end. */
        public Integer expiresInDays;
        public boolean all;
        public List<Integer> groupIds;
        public List<Integer> deviceIds;
        /** How the console describes the recipients (e.g. "Colombia, Ecuador + 2 dispositivos"). */
        public String targetLabel;
    }

    @ApiOperation(value = "List announcements", notes = "Newest first, with receipt counts (total, received, seen, acked).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<Integer> c = SecurityContext.get().getCurrentCustomerId();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        return Response.OK(mapper.list(c.get()));
    }

    @ApiOperation(value = "One announcement with each device's receipt")
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") int id) {
        Optional<Integer> c = SecurityContext.get().getCurrentCustomerId();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> a = mapper.find(c.get(), id);
        if (a == null) return Response.ERROR("error.announcement.notFound");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("announcement", a);
        out.put("receipts", mapper.receipts(id));
        return Response.OK(out);
    }

    @ApiOperation(value = "Send an announcement", notes = "Targets: all, folders (with their sub-folders) and/or devices.")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Body b) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        int customerId = c.get();
        String title = b == null || b.title == null ? "" : b.title.trim();
        if (title.isEmpty() || title.length() > 200) return Response.ERROR("error.announcement.title");
        String text = b.body == null ? null : b.body.trim();
        if (text != null && text.length() > 5000) return Response.ERROR("error.announcement.body");
        String url = b.mediaUrl == null || b.mediaUrl.trim().isEmpty() ? null : b.mediaUrl.trim();
        String type = url == null ? null : b.mediaType;
        if (url != null && (!(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 1000
                || type == null || !MEDIA_TYPES.contains(type))) {
            return Response.ERROR("error.announcement.media");
        }

        // Recipients: every device, folders (and their sub-folders), and single devices of this customer.
        Set<Integer> groups = new HashSet<>();
        if (b.groupIds != null) {
            for (Integer g : b.groupIds) {
                if (g != null) groups.addAll(commandDAO.groupSubtree(customerId, g));
            }
        }
        Set<Integer> ids = b.deviceIds == null ? Set.of() : new HashSet<>(b.deviceIds);
        Set<String> numbers = new LinkedHashSet<>();
        for (DeviceScopeRow r : commandDAO.listDeviceScopes(customerId)) {
            if (b.all || ids.contains(r.getId()) || (r.getGroupId() != null && groups.contains(r.getGroupId()))) {
                numbers.add(r.getNumber());
            }
        }
        if (numbers.isEmpty()) return Response.ERROR("error.announcement.noDevices");
        if (numbers.size() > MAX_DEVICES) return Response.ERROR("error.announcement.tooMany");

        long now = System.currentTimeMillis();
        Map<String, Object> a = new HashMap<>();
        a.put("customerId", customerId);
        a.put("title", title);
        a.put("body", text);
        a.put("mediaUrl", url);
        a.put("mediaType", type);
        a.put("mandatory", b.mandatory);
        a.put("targetLabel", b.targetLabel == null ? null : b.targetLabel.substring(0, Math.min(300, b.targetLabel.length())));
        a.put("createdBy", SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse(null));
        a.put("createdAt", now);
        a.put("expiresAt", b.expiresInDays == null || b.expiresInDays <= 0 ? null : now + Math.min(365, b.expiresInDays) * 86_400_000L);
        mapper.insert(a);
        int id = ((Number) a.get("id")).intValue();
        Map<String, Object> row = mapper.find(customerId, id);
        for (String n : numbers) {
            mapper.addReceipt(id, n);
            sender.send(n, row, true);
        }
        logger.info("Announcement {} sent to {} device(s) (customer {})", id, numbers.size(), customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("devices", numbers.size());
        return Response.OK(out);
    }

    @ApiOperation(value = "Withdraw an announcement", notes = "Stops delivering it and removes it from the phones' app.")
    @POST
    @Path("/{id}/withdraw")
    @Produces(MediaType.APPLICATION_JSON)
    public Response withdraw(@PathParam("id") int id) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        if (mapper.withdraw(c.get(), id, System.currentTimeMillis()) == 0) return Response.ERROR("error.announcement.notFound");
        for (String n : mapper.sentTo(id)) sender.withdraw(n, id);
        return Response.OK();
    }

    private static Optional<Integer> editor() {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }
}
