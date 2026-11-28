package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.util.CommandPayloads;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of a device's command history as the console sees it. Deliberately has NO payload: payloads carry secrets
 * in plain text ({@code device.passcodeReset} the new device passcode; {@code config.apply} and {@code kiosk.enter}
 * the kiosk exit password as entered), and the history is readable by every user of the customer.
 * {@link #subject} is a safe, server-derived label instead.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandHistoryView {
    private Integer id;
    private String type;
    private String status;
    private String detail;
    private Long createdAt;
    private Long deliveredAt;
    private Long completedAt;
    /** The package an {@code app.install}/{@code app.uninstall} targets; null for every other type. */
    private String subject;
    /** Who queued it (a console login, or "sistema" for automatic commands) and from which address. */
    private String issuerName;
    private String issuerIp;
    /** Times it was handed to the device; more than one means a delivery went unanswered and was repeated. */
    private Integer deliveries;
    /** Milliseconds from queueing to delivery, and from queueing to the device's result; null while not reached. */
    private Long deliveryMs;
    private Long totalMs;
    /**
     * Only on the answer to queueing a command: whether the device's live channel was open at that moment. False
     * means the command stays in the queue and runs when the device connects again.
     */
    private Boolean deviceOnline;
    /** Only on the answer to queueing: how the device will learn of the command (live | push | poll) and the usual wait. */
    private String wakeChannel;
    private Integer wakeExpectedSeconds;

    /** The span between two instants, or null when either is missing or they are out of order. */
    static Long span(Long from, Long to) {
        return from == null || to == null || to < from ? null : Long.valueOf(to - from);
    }

    public static CommandHistoryView from(AgentCommand c) {
        CommandHistoryView v = new CommandHistoryView();
        v.setId(c.getId());
        v.setType(c.getType());
        v.setStatus(c.getStatus());
        v.setDetail(c.getDetail());
        v.setCreatedAt(c.getCreatedAt());
        v.setDeliveredAt(c.getDeliveredAt());
        v.setCompletedAt(c.getCompletedAt());
        v.setSubject(subjectOf(c.getType(), c.getPayload()));
        v.setIssuerName(c.getIssuerName());
        v.setIssuerIp(c.getIssuerIp());
        v.setDeliveries(c.getDeliveries());
        v.setDeliveryMs(span(c.getCreatedAt(), c.getDeliveredAt()));
        // Only a result the device reported is a latency; an expiry or a cancellation is not.
        boolean ran = "done".equals(c.getStatus()) || "failed".equals(c.getStatus()) || "unsupported".equals(c.getStatus());
        v.setTotalMs(ran ? span(c.getCreatedAt(), c.getCompletedAt()) : null);
        return v;
    }

    public static List<CommandHistoryView> fromAll(List<AgentCommand> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<CommandHistoryView> out = new ArrayList<CommandHistoryView>(rows.size());
        for (AgentCommand c : rows) out.add(from(c));
        return out;
    }

    /**
     * The history label for a command: its target package for {@code app.install}/{@code app.uninstall}
     * (exact type match — command types are exact strings across the protocol), null for every other type.
     * Never throws; the payload parsing rules live in {@link CommandPayloads#packageNameOf}.
     */
    public static String subjectOf(String type, String payloadJson) {
        // A wipe / lost mode: what was ordered (intent and options), so it stays readable in the history.
        if (com.hmdm.util.ProtectionCommands.WIPE.equals(type) || com.hmdm.util.ProtectionCommands.LOST_MODE.equals(type)) {
            return com.hmdm.util.ProtectionCommands.subjectOf(type, payloadJson);
        }
        if (!"app.install".equals(type) && !"app.uninstall".equals(type)) return null;
        return CommandPayloads.packageNameOf(payloadJson);
    }
}
