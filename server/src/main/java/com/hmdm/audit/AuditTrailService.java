/*
 *
 * DallyControl — builds and writes the rows of the audit trail.
 *
 * AuditTrailFilter calls {@link #before} just before a request runs (to read the request and, for edits and deletes,
 * how the target looks now) and {@link #after} once it has answered. Whether a request is recorded, and under what
 * name, is decided here from AuditCatalog:
 *   - every route the catalog names (state changes, sign-ins, exports) is recorded, whatever its result;
 *   - a state-changing request under /private that the catalog does not know yet is recorded with a generic label;
 *   - a read is recorded only when it was refused (no permission / outside the user's folders).
 * The row is completed (who, names of what was touched, before/after) and inserted by one background thread, so a
 * request never waits for the audit and rows are chained in the order they are written. Nothing here may throw into
 * a request: a failure to audit is logged, not propagated.
 *
 */

package com.hmdm.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.AuditTrailDAO;
import com.hmdm.persistence.domain.AuditEntry;
import com.hmdm.persistence.mapper.AuditTrailMapper;
import com.hmdm.util.ExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Singleton
public class AuditTrailService {
    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** For code that is not built by Guice (same pattern as UserScope.INSTANCE). */
    public static volatile AuditTrailService INSTANCE;

    static final int MAX_DEVICES = 2000;
    private static final long REMOTE_SESSION_WINDOW_MS = 12L * 3600_000L;
    private static final long DENIED_READ_REPEAT_MS = 60_000L;

    private final AuditTrailDAO dao;
    private final AuditTrailMapper db;
    private final ThreadPoolExecutor writer;
    private final Map<String, Long> deniedReads = new ConcurrentHashMap<>();

    @Inject
    public AuditTrailService(AuditTrailDAO dao) {
        this.dao = dao;
        this.db = dao.lookups();
        this.writer = ExecutorRegistry.register(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(20000), r -> {
            Thread t = new Thread(r, "audit-trail-writer");
            t.setDaemon(true);
            return t;
        }));
        INSTANCE = this;
    }

    /** What the filter saw of a request. */
    public static final class Seen {
        public String method;
        /** Path under /rest, e.g. "/private/devices/12". */
        public String path;
        public String query;
        public String ip;
        public String userAgent;
        public String contentType;
        public long contentLength;
        public long at;
        /** The signed-in user before the request ran (null on sign-in). */
        public Integer userId;
        /** The JSON or text body, when it was small enough to keep. */
        public String rawBody;

        AuditCatalog.Match match;
        JsonNode body;
        String targetType;
        String targetId;
        Map<String, Object> deviceBefore;
        Map<String, String> before;
    }

    // --- before the request ----------------------------------------------------------------------------------------

    /** Reads what must be read before the request changes it. Never throws. */
    public void before(Seen s) {
        try {
            s.match = AuditCatalog.find(s.method, s.path);
            if (s.match == null || s.match.rule.kind != AuditCatalog.Kind.AUDIT) return;
            AuditCatalog.Rule rule = s.match.rule;
            s.body = AuditRedactor.parse(s.rawBody);
            if ("/public/passwordReset/reset".equals(s.path) && s.body != null) {
                // The token is consumed by the request: find whose it is now.
                String token = text(s.body, "passwordResetToken");
                if (token != null) {
                    Map<String, Object> u = db.userByResetToken(token);
                    if (u != null) s.userId = intOf(u.get("id"));
                }
            }
            if (rule.targetType == null) return;
            s.targetType = rule.targetType;
            s.targetId = source(rule.targetSource, s);
            if (s.targetId == null) return;
            if ("device".equals(rule.targetType)) {
                s.deviceBefore = db.deviceByNumber(s.targetId);
            } else if ("deviceId".equals(rule.targetType)) {
                Integer id = parseInt(s.targetId);
                if (id != null) s.deviceBefore = db.deviceById(id);
            }
            if (rule.snapshot) s.before = snapshot(rule.targetType, s.targetId);
        } catch (Exception e) {
            log.warn("Audit: could not read the request {} {} before it ran: {}", s.method, s.path, e.toString());
        }
    }

    // --- after the request -----------------------------------------------------------------------------------------

    /**
     * Decides whether the request is recorded and queues the row. Never throws.
     *
     * @param userIdAfter the signed-in user after the request (a sign-in sets it), or null
     */
    public void after(Seen s, int httpStatus, String responseStart, boolean responseJson, Integer userIdAfter) {
        try {
            AuditCatalog.Rule rule = s.match == null ? null : s.match.rule;
            boolean signIn = rule != null && "session.login".equals(rule.action);
            AuditOutcome outcome = AuditOutcome.of(httpStatus, responseStart, responseJson, signIn);
            Integer userId = s.userId != null ? s.userId : userIdAfter;
            if (!shouldRecord(s, rule, outcome, userId)) return;
            final long now = System.currentTimeMillis();
            writer.execute(() -> {
                try {
                    AuditEntry e = build(s, rule, outcome, httpStatus, userId, now);
                    if (e != null) dao.append(e);
                } catch (Exception ex) {
                    log.error("Audit: could not record {} {}: {}", s.method, s.path, ex.toString(), ex);
                }
            });
        } catch (Exception e) {
            log.error("Audit: could not queue {} {}: {}", s.method, s.path, e.toString());
        }
    }

    boolean shouldRecord(Seen s, AuditCatalog.Rule rule, AuditOutcome outcome, Integer userId) {
        boolean priv = s.path.startsWith("/private/");
        if (rule != null && rule.kind == AuditCatalog.Kind.AUDIT) {
            // Under /private an anonymous request cannot be attributed to anyone (an expired session): not recorded.
            return !priv || userId != null;
        }
        if (rule != null && rule.kind == AuditCatalog.Kind.DEVICE) return false;
        if (!priv || userId == null) return false;
        boolean denied = AuditOutcome.DENIED.equals(outcome.result);
        if (rule == null && AuditCatalog.isMutating(s.method)) return true;
        // Reads (and POST queries, upload parts, interface preferences): only a refusal is worth a row, once a minute.
        if (!denied) return false;
        String key = userId + "|" + s.method + "|" + s.path;
        long now = System.currentTimeMillis();
        Long last = deniedReads.get(key);
        if (last != null && now - last < DENIED_READ_REPEAT_MS) return false;
        if (deniedReads.size() > 5000) deniedReads.clear();
        deniedReads.put(key, now);
        return true;
    }

    // --- system rows -----------------------------------------------------------------------------------------------

    /** A row not caused by a request (the retention purge). Written in order with the others. */
    public void recordSystem(Integer customerId, String category, String action, String label, ObjectNode detail) {
        final long now = System.currentTimeMillis();
        writer.execute(() -> {
            try {
                AuditEntry e = new AuditEntry();
                e.createdAt = now;
                e.customerId = customerId;
                e.login = "sistema";
                e.userName = "Sistema (tarea automática)";
                e.category = category;
                e.action = action;
                e.label = label;
                e.result = AuditOutcome.OK;
                e.detail = AuditRedactor.write(detail);
                dao.append(e);
            } catch (Exception ex) {
                log.error("Audit: could not record the system row {}: {}", action, ex.toString());
            }
        });
    }

    /** Waits until everything queued so far is written (tests, and the purge before it counts). */
    public void flush() {
        try {
            writer.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Audit: flush did not finish: {}", e.toString());
        }
    }

    // --- building the row ------------------------------------------------------------------------------------------

    AuditEntry build(Seen s, AuditCatalog.Rule rule, AuditOutcome outcome, int httpStatus, Integer userId, long now) {
        AuditEntry e = new AuditEntry();
        e.createdAt = s.at > 0 ? s.at : now;
        e.method = s.method;
        e.path = clamp(s.path, 300);
        e.ip = clamp(s.ip, 64);
        e.userAgent = clamp(s.userAgent, 300);
        e.httpStatus = httpStatus;
        e.result = outcome.result;
        e.message = clamp(outcome.message, 300);

        JsonNode body = s.body != null ? s.body : AuditRedactor.parse(s.rawBody);
        ObjectNode detail = JsonNodeFactory.instance.objectNode();

        // who
        Map<String, Object> user = userId == null ? null : db.userById(userId);
        boolean signIn = rule != null && "session.login".equals(rule.action);
        if (user == null && body != null && (signIn || "/public/passwordReset/reset".equals(s.path))) {
            String login = text(body, "login");
            if (login != null) {
                user = db.userByLogin(login);
                if (user == null) e.login = clamp(login, 120);
            }
        }
        if (user == null && rule != null && "session.password.recover".equals(rule.action)) {
            String login = s.match.params.get("username");
            user = login == null ? null : db.userByLogin(decode(login));
            if (user == null) e.login = clamp(login, 120);
        }
        if (user != null) {
            e.userId = intOf(user.get("id"));
            e.login = str(user.get("login"));
            e.userName = str(user.get("name"));
            e.customerId = intOf(user.get("customerid"));
            e.roleId = intOf(user.get("roleid"));
            e.roleName = str(user.get("rolename"));
        }

        // what
        String commandType = null;
        if (rule != null && rule.kind == AuditCatalog.Kind.AUDIT) {
            e.category = rule.category;
            e.action = rule.action;
            e.label = rule.label;
            if (rule.failLabel != null && !AuditOutcome.OK.equals(outcome.result)) e.label = rule.failLabel;
            if (rule.command) {
                commandType = commandType(body);
                String t = commandType == null ? "" : commandType.trim().toLowerCase(Locale.ROOT);
                if ("remote.vnc.stop".equals(t) && "device.command".equals(rule.action)) {
                    e.category = AuditCatalog.REMOTE;
                    e.action = "remote.session.stop";
                    e.label = "Fin de sesión de control remoto";
                } else {
                    e.action = rule.action + ":" + (t.isEmpty() ? "?" : t);
                    e.label = rule.label + ": " + AuditCatalog.commandLabel(commandType);
                }
            }
        } else {
            boolean read = !AuditCatalog.isMutating(s.method) || (rule != null && rule.kind != AuditCatalog.Kind.AUDIT);
            e.category = AuditCatalog.guessCategory(s.path);
            e.action = (read ? "read:" : "other:") + s.method + " " + clamp(templateOf(s.path), 70);
            e.label = read ? "Consulta denegada: " + s.method + " " + s.path
                    : "Operación: " + s.method + " " + s.path;
            if (rule != null && rule.reason != null && read) e.label = "Consulta denegada: " + rule.reason;
        }
        e.label = clamp(e.label, 200);

        // on what
        Set<String> numbers = new LinkedHashSet<>();
        Set<Integer> groups = new LinkedHashSet<>();
        int customer = e.customerId == null ? -1 : e.customerId;
        resolveTarget(s, rule, body, e, customer, numbers, groups, detail);
        extraTargets(s, body, customer, numbers, groups, detail, e);
        e.deviceNumbersLiteral = textArray(numbers);
        e.groupIdsLiteral = intArray(groups);

        // detail
        if (AuditOutcome.OK.equals(outcome.result) && rule != null && rule.snapshot && s.targetId != null) {
            Map<String, String> after = snapshot(rule.targetType, s.targetId);
            if (s.before != null && after != null) {
                ObjectNode changes = AuditDiff.changes(s.before, after);
                if (changes.size() > 0) detail.set("cambios", changes);
                else detail.put("cambios", "sin cambios en los datos guardados");
            } else if (s.before != null) {
                detail.set("eliminado", AuditDiff.summary(s.before));
            }
        }
        JsonNode clean = body == null ? AuditRedactor.sanitize(s.rawBody) : AuditRedactor.clean(body, 0);
        if (clean != null && !(clean.isContainerNode() && clean.size() == 0)) detail.set("solicitud", clean);
        if (s.query != null && !s.query.isEmpty() && (rule == null || rule.sensitiveRead || !AuditCatalog.isMutating(s.method))) {
            ObjectNode q = queryNode(s.query);
            if (q.size() > 0) detail.set("consulta", q);
        }
        if (s.rawBody == null && s.contentLength > 0 && s.contentType != null
                && s.contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            detail.put("archivo", "carga de " + humanBytes(s.contentLength));
        }
        if ("remote.session.stop".equals(e.action) && AuditOutcome.OK.equals(e.result) && !numbers.isEmpty()) {
            Long started = dao.lastRemoteStart(numbers.iterator().next(), e.createdAt - REMOTE_SESSION_WINDOW_MS);
            if (started != null && started <= e.createdAt) {
                e.durationMs = (int) Math.min(Integer.MAX_VALUE, e.createdAt - started);
                detail.put("duracion", humanDuration(e.durationMs));
            }
        }
        if (commandType != null && AuditCatalog.isDisruptive(commandType)) detail.put("accionDisruptiva", true);
        e.detail = AuditRedactor.write(detail);
        return e;
    }

    /** The rule's own target: its id, its name and the devices / folders it stands for. */
    private void resolveTarget(Seen s, AuditCatalog.Rule rule, JsonNode body, AuditEntry e, int customer,
                               Set<String> numbers, Set<Integer> groups, ObjectNode detail) {
        if (rule == null || rule.targetType == null) return;
        String type = rule.targetType;
        if ("devices".equals(type)) {
            e.targetType = "devices";
            String key = rule.targetSource.substring(rule.targetSource.indexOf(':') + 1);
            List<Map<String, Object>> rows = devices(body, key, customer);
            addDevices(rows, numbers, groups);
            describeDevices(rows, countOf(body, key), e, detail);
            return;
        }
        if ("device".equals(type) || "deviceId".equals(type)) {
            e.targetType = "device";
            Map<String, Object> d = s.deviceBefore;
            if (d == null && s.targetId != null) {
                d = "device".equals(type) ? db.deviceByNumber(s.targetId)
                        : (parseInt(s.targetId) == null ? null : db.deviceById(parseInt(s.targetId)));
            }
            if (d != null && customer >= 0 && sameCustomer(d, customer)) {
                e.targetId = str(d.get("number"));
                String desc = str(d.get("description"));
                e.targetName = clamp(desc == null || desc.trim().isEmpty() ? e.targetId : desc, 255);
                List<Map<String, Object>> one = new ArrayList<>();
                one.add(d);
                addDevices(one, numbers, groups);
            } else if (s.targetId != null) {
                e.targetId = clamp(s.targetId, 120);
                if ("device".equals(type)) numbers.add(e.targetId);
            }
            return;
        }
        e.targetType = type;
        e.targetId = clamp(s.targetId, 120);
        Integer id = parseInt(s.targetId);
        if (id == null) {
            // A creation: the name the request gives it.
            if (body != null) e.targetName = clamp(firstText(body, "name", "login", "title", "label"), 255);
            return;
        }
        Map<String, String> row = s.before != null ? s.before : snapshot(type, s.targetId);
        if (row != null && (customer < 0 || row.get("customerid") == null || String.valueOf(customer).equals(row.get("customerid")))) {
            e.targetName = clamp(AuditTargets.name(type, row), 255);
        }
        if (e.targetName == null && body != null) e.targetName = clamp(firstText(body, "name", "login", "title", "label"), 255);
        if ("group".equals(type)) {
            groups.add(id);
            if (rule.command && customer >= 0) {
                // A command to a folder reaches every device below it.
                List<Integer> subtree = db.groupSubtree(customer, id);
                if (!subtree.isEmpty()) {
                    groups.addAll(subtree);
                    List<String> ns = db.deviceNumbersOfGroups(customer, subtree, MAX_DEVICES);
                    numbers.addAll(ns);
                    detail.put("dispositivosAlcanzados", ns.size());
                }
            }
        } else if ("user".equals(type)) {
            // A folder administrator sees what is done to the users of their folders.
            groups.addAll(db.userFolderIds(id));
        }
    }

    /** Devices and folders the body names besides the rule's own target (so scoping and the device tab see them). */
    private void extraTargets(Seen s, JsonNode body, int customer, Set<String> numbers, Set<Integer> groups,
                              ObjectNode detail, AuditEntry e) {
        if (body == null || !body.isObject()) return;
        if (!"devices".equals(e.targetType)) {
            for (String key : new String[]{"deviceIds", "deviceNumbers", "numbers", "canaryDeviceNumbers"}) {
                if (!body.has(key) || !body.get(key).isArray() || body.get(key).size() == 0) continue;
                List<Map<String, Object>> rows = devices(body, key, customer);
                addDevices(rows, numbers, groups);
                if (!rows.isEmpty() && !detail.has("dispositivos")) {
                    ObjectNode tmp = JsonNodeFactory.instance.objectNode();
                    AuditEntry ignore = new AuditEntry();
                    describeDevices(rows, countOf(body, key), ignore, tmp);
                    if (tmp.has("dispositivos")) detail.set("dispositivos", tmp.get("dispositivos"));
                }
            }
        }
        for (String key : new String[]{"groupId", "parentId"}) {
            Integer g = parseInt(text(body, key));
            if (g != null) {
                groups.add(g);
                String name = db.groupName(g);
                if (name != null) detail.put("groupId".equals(key) ? "carpeta" : "carpetaSuperior", name);
            }
        }
        if (body.has("groupIds") && body.get("groupIds").isArray()) {
            for (JsonNode g : body.get("groupIds")) if (g.canConvertToInt()) groups.add(g.asInt());
        }
        Integer cfg = parseInt(text(body, "configurationId"));
        if (cfg != null) {
            String name = db.configurationName(cfg);
            if (name != null) detail.put("politica", name);
        }
    }

    private List<Map<String, Object>> devices(JsonNode body, String key, int customer) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body == null || !body.has(key) || !body.get(key).isArray()) return out;
        List<Integer> ids = new ArrayList<>();
        List<String> ns = new ArrayList<>();
        for (JsonNode n : body.get(key)) {
            if (ids.size() + ns.size() >= MAX_DEVICES) break;
            if (n.isIntegralNumber()) ids.add(n.asInt());
            else if (n.isTextual() && !n.asText().trim().isEmpty()) ns.add(n.asText().trim());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!ids.isEmpty()) rows.addAll(db.devicesByIds(ids));
        if (!ns.isEmpty()) rows.addAll(db.devicesByNumbers(ns));
        for (Map<String, Object> r : rows) if (customer >= 0 && sameCustomer(r, customer)) out.add(r);
        return out;
    }

    private static int countOf(JsonNode body, String key) {
        return body != null && body.has(key) && body.get(key).isArray() ? body.get(key).size() : 0;
    }

    private static void addDevices(Collection<Map<String, Object>> rows, Set<String> numbers, Set<Integer> groups) {
        for (Map<String, Object> d : rows) {
            String n = str(d.get("number"));
            if (n != null) numbers.add(n);
            String gs = str(d.get("groups"));
            if (gs != null) for (String g : gs.split(",")) {
                Integer id = parseInt(g);
                if (id != null) groups.add(id);
            }
        }
    }

    private static void describeDevices(List<Map<String, Object>> rows, int asked, AuditEntry e, ObjectNode detail) {
        int n = Math.max(asked, rows.size());
        if (rows.size() == 1 && asked <= 1) {
            e.targetId = str(rows.get(0).get("number"));
            String desc = str(rows.get(0).get("description"));
            e.targetName = desc == null || desc.trim().isEmpty() ? e.targetId : desc;
        } else if (n > 0) {
            e.targetName = n + " dispositivos";
        }
        if (rows.isEmpty()) return;
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        int i = 0;
        for (Map<String, Object> d : rows) {
            if (i++ >= 30) {
                list.add("… (" + rows.size() + " en total)");
                break;
            }
            String number = str(d.get("number"));
            String desc = str(d.get("description"));
            list.add(desc == null || desc.trim().isEmpty() || desc.equals(number) ? number : desc + " (" + number + ")");
        }
        detail.set("dispositivos", list);
    }

    private static boolean sameCustomer(Map<String, Object> row, int customer) {
        Integer c = intOf(row.get("customerid"));
        return c == null || c == customer;
    }

    // --- snapshots -------------------------------------------------------------------------------------------------

    /** How the target's row looks now (columns as text, plus a few resolved names); null when it does not exist. */
    Map<String, String> snapshot(String type, String id) {
        AuditTargets.Type t = AuditTargets.of(type);
        Integer n = parseInt(id);
        if (t == null || n == null) return null;
        String json = db.rowJson(t.table, n);
        if (json == null) return null;
        Map<String, String> row = new LinkedHashMap<>();
        try {
            JsonNode node = JSON.readTree(json);
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                JsonNode v = f.getValue();
                row.put(f.getKey(), v == null || v.isNull() ? null : (v.isValueNode() ? v.asText() : v.toString()));
            }
        } catch (Exception ex) {
            return null;
        }
        // Names instead of ids, and what lives in other tables.
        Integer cfg = parseInt(row.get("configurationid"));
        if (cfg != null && row.containsKey("configurationid")) row.put("configurationid", nameOr(db.configurationName(cfg), cfg));
        Integer parent = parseInt(row.get("parentid"));
        if (parent != null) row.put("parentid", nameOr(db.groupName(parent), parent));
        if ("user".equals(type)) {
            Integer role = parseInt(row.get("userroleid"));
            if (role != null) row.put("userroleid", nameOr(db.roleName(role), role));
            row.put("carpetas", db.userFolders(n));
        } else if ("role".equals(type)) {
            row.put("permisos", db.rolePermissions(n));
        } else if ("deviceId".equals(type) || "device".equals(type)) {
            row.put("carpetas", db.deviceFolders(n));
        }
        return row;
    }

    private static String nameOr(String name, int id) {
        return name == null ? String.valueOf(id) : name;
    }

    // --- small helpers ---------------------------------------------------------------------------------------------

    /** Where a rule says its target id is: "path:name", "body:field" or "query:name". */
    private static String source(String source, Seen s) {
        if (source == null) return null;
        int i = source.indexOf(':');
        String where = source.substring(0, i);
        String name = source.substring(i + 1);
        switch (where) {
            case "path":
                String v = s.match.params.get(name);
                return v == null ? null : decode(v);
            case "body":
                return s.body == null ? null : text(s.body, name);
            case "query":
                return queryParam(s.query, name);
            default:
                return null;
        }
    }

    static String commandType(JsonNode body) {
        if (body == null) return null;
        String t = text(body, "type");
        if (t == null && body.has("command")) t = text(body.get("command"), "type");
        return t;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.isObject() || !n.has(field)) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) return null;
        String s = v.asText();
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) return v;
        }
        return null;
    }

    static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : decode(p.substring(eq + 1));
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    static ObjectNode queryNode(String query) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (query == null) return out;
        int n = 0;
        for (String p : query.split("&")) {
            if (p.isEmpty() || ++n > 20) continue;
            int eq = p.indexOf('=');
            String k = decode(eq < 0 ? p : p.substring(0, eq));
            String v = eq < 0 ? "" : decode(p.substring(eq + 1));
            out.put(k, AuditRedactor.isSecretName(k) ? AuditRedactor.MASK : AuditRedactor.cut(v));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** "/private/x/123/y" → "/private/x/{id}/y", so unknown routes group under one action key. */
    static String templateOf(String path) {
        return path == null ? "" : path.replaceAll("/\\d+(?=/|$)", "/{id}");
    }

    static String textArray(Collection<String> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (String v : values) {
            if (v == null) continue;
            if (i++ > 0) sb.append(',');
            sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    static String intArray(Collection<Integer> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Integer v : values) {
            if (v == null) continue;
            if (i++ > 0) sb.append(',');
            sb.append(v.intValue());
        }
        return sb.append('}').toString();
    }

    static String humanDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
    }

    static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    static Integer parseInt(String s) {
        if (s == null) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        return parseInt(String.valueOf(o));
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
