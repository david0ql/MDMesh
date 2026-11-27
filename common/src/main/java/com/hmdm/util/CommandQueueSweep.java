package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;

import java.util.List;
import java.util.Set;

/**
 * <p>Applies {@link CommandPolicy} to a device's open commands: expires the ones whose wait is over and puts back
 * in the queue the delivered ones the device never answered. Runs on every check-in (before commands are handed
 * out) and when the console reads a device's history.</p>
 */
public final class CommandQueueSweep {

    /** Detail left on a command that ends without running. Shown in the console, so in Spanish. */
    public static final String DETAIL_EXPIRED_PENDING = "Venció sin entregarse: el equipo no se conectó a tiempo";
    public static final String DETAIL_EXPIRED_DELIVERED = "Entregado, pero el equipo nunca confirmó el resultado";
    public static final String DETAIL_SUPERSEDED = "No se reenvió: una orden posterior ya la reemplazó";

    /** The queue, as the sweep needs it. */
    public interface Queue {
        List<AgentCommand> listOpen(String deviceNumber);

        void expire(Integer id, long now, String detail);

        void requeue(Integer id);

        /** A command newer than {@code id}, of one of {@code lowerCaseTypes}, already reached the device. */
        boolean newerDelivered(String deviceNumber, Integer id, Set<String> lowerCaseTypes);
    }

    /** What one sweep did. */
    public static final class Outcome {
        public int expired;
        public int requeued;
        public int superseded;
    }

    private CommandQueueSweep() {
    }

    /**
     * @param ledger the device's agent remembers across restarts which commands it ran
     *               ({@link CommandPolicy#LEDGER_CAPABILITY})
     */
    public static Outcome run(Queue queue, String deviceNumber, boolean ledger, long now) {
        return run(queue, deviceNumber, ledger, now, true);
    }

    /** Only close what is overdue; hand nothing out again (for a sweep made while the phone is not checking in). */
    public static Outcome expireOnly(Queue queue, String deviceNumber, long now) {
        return run(queue, deviceNumber, false, now, false);
    }

    private static Outcome run(Queue queue, String deviceNumber, boolean ledger, long now, boolean redeliver) {
        Outcome out = new Outcome();
        for (AgentCommand c : queue.listOpen(deviceNumber)) {
            CommandPolicy.Action action = CommandPolicy.decide(c, now, ledger, false);
            if (!redeliver && action != CommandPolicy.Action.EXPIRE) continue;
            if (action == CommandPolicy.Action.REQUEUE) {
                Set<String> family = CommandPolicy.familyTypes(c.getType());
                if (!family.isEmpty() && queue.newerDelivered(deviceNumber, c.getId(), family)) {
                    action = CommandPolicy.Action.SUPERSEDE;
                }
            }
            switch (action) {
                case EXPIRE:
                    queue.expire(c.getId(), now, "pending".equals(c.getStatus())
                            ? DETAIL_EXPIRED_PENDING : DETAIL_EXPIRED_DELIVERED);
                    out.expired++;
                    break;
                case SUPERSEDE:
                    queue.expire(c.getId(), now, DETAIL_SUPERSEDED);
                    out.superseded++;
                    break;
                case REQUEUE:
                    queue.requeue(c.getId());
                    out.requeued++;
                    break;
                default:
                    break;
            }
        }
        return out;
    }
}
