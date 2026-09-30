package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.mapper.AppGroupMapper;
import com.hmdm.util.DcPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A policy's effective apps: its own app rows plus the apps of the app groups it uses (latest library version,
 * action install). A row of the policy wins over a group for the same package (e.g. "Desinstalar"). Also says which
 * packages stay out of the kiosk: the policy's own "not in kiosk" apps and the apps of groups used without kiosk
 * (unless another group puts them in the kiosk).
 */
@Singleton
public class PolicyApps {

    private static final Logger logger = LoggerFactory.getLogger(PolicyApps.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppGroupMapper mapper;

    @Inject
    public PolicyApps(AppGroupMapper mapper) {
        this.mapper = mapper;
    }

    public static final class Resolved {
        public final List<Application> apps;
        public final Set<String> notInKiosk;

        Resolved(List<Application> apps, Set<String> notInKiosk) {
            this.apps = apps;
            this.notInKiosk = notInKiosk;
        }
    }

    public Resolved resolve(Configuration cfg, List<Application> rows) {
        List<Application> base = rows == null ? Collections.<Application>emptyList() : rows;
        DcPolicy dc = DcPolicy.parse(cfg == null ? null : cfg.getDcPolicy());
        Set<String> notInKiosk = new HashSet<String>();
        if (dc.getNotInKiosk() != null) notInKiosk.addAll(dc.getNotInKiosk());
        if (dc.getAppGroups() == null || dc.getAppGroups().isEmpty()) return new Resolved(base, notInKiosk);
        try {
            Map<Integer, Boolean> kioskOf = new HashMap<Integer, Boolean>();
            for (DcPolicy.AppGroupRef r : dc.getAppGroups()) kioskOf.put(r.getId(), Boolean.TRUE.equals(r.getKiosk()));
            Map<Integer, Boolean> appKiosk = new HashMap<Integer, Boolean>(); // app id -> shown in kiosk by some group
            for (Map<String, Object> g : mapper.appIdsOf(cfg.getCustomerId(), new ArrayList<Integer>(kioskOf.keySet()))) {
                boolean kiosk = Boolean.TRUE.equals(kioskOf.get(((Number) g.get("id")).intValue()));
                for (Integer appId : appIds(g.get("appids"))) appKiosk.merge(appId, kiosk, (x, y) -> x || y);
            }
            if (appKiosk.isEmpty()) return new Resolved(base, notInKiosk);
            Set<String> rowPkgs = new HashSet<String>();
            for (Application a : base) if (a != null && a.getPkg() != null) rowPkgs.add(a.getPkg().trim());
            List<Application> out = new ArrayList<Application>(base);
            Set<String> added = new LinkedHashSet<String>();
            for (Application a : mapper.latestApps(cfg.getCustomerId(), new ArrayList<Integer>(appKiosk.keySet()))) {
                if (a == null || a.getPkg() == null) continue;
                String pkg = a.getPkg().trim();
                if (pkg.isEmpty() || rowPkgs.contains(pkg) || !added.add(pkg)) continue;
                out.add(a);
                if (!Boolean.TRUE.equals(appKiosk.get(a.getId()))) notInKiosk.add(pkg);
            }
            return new Resolved(out, notInKiosk);
        } catch (Exception e) {
            logger.warn("App groups of policy {} not resolved: {}", cfg.getId(), e.getMessage());
            return new Resolved(base, notInKiosk);
        }
    }

    /** Ids of the customer's policies that use any of these app groups. */
    public Set<Integer> policiesUsing(int customerId, Set<Integer> groupIds) {
        Set<Integer> out = new HashSet<Integer>();
        for (Map<String, Object> p : mapper.policiesWithGroups(customerId)) {
            DcPolicy dc = DcPolicy.parse(p.get("dcpolicy") == null ? null : String.valueOf(p.get("dcpolicy")));
            if (dc.getAppGroups() == null) continue;
            for (DcPolicy.AppGroupRef r : dc.getAppGroups()) {
                if (groupIds == null || groupIds.contains(r.getId())) { out.add(((Number) p.get("id")).intValue()); break; }
            }
        }
        return out;
    }

    /** Groups (ids) of the customer that contain this app. */
    public Set<Integer> groupsWithApp(int customerId, int appId) {
        Set<Integer> out = new HashSet<Integer>();
        for (Map<String, Object> g : mapper.list(customerId)) {
            if (appIds(g.get("appids")).contains(appId)) out.add(((Number) g.get("id")).intValue());
        }
        return out;
    }

    public static List<Integer> appIds(Object json) {
        if (json == null) return Collections.emptyList();
        try {
            List<Integer> out = new ArrayList<Integer>();
            for (com.fasterxml.jackson.databind.JsonNode n : JSON.readTree(String.valueOf(json))) if (n.canConvertToInt()) out.add(n.asInt());
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
