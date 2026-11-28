package com.hmdm.notification.push;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>Third wake channel, for phones without Google services and without data: a text message to the SIM's number
 * asking the agent to check in. This is the provider seam only — no SMS gateway is contracted, so the only
 * implementation is {@link Simulator} (set {@code SMS_WAKE_PROVIDER=simulator}), which records what would be sent.
 * See docs/DESPERTADOR-FCM.md, "SMS de despertador (diseño)" for what a real provider and the agent side need.</p>
 */
public interface SmsWakeProvider {

    /** Shown in the console's server settings. */
    String name();

    /**
     * @param phone the SIM's number in international format
     * @param text  the wake text; carries no command and no secret
     * @return null when accepted by the provider, otherwise why not
     */
    String send(String phone, String text);

    /** The text an agent would recognise: a fixed prefix plus the device number, nothing else. */
    static String wakeText(String deviceNumber) {
        return "DCWAKE " + deviceNumber;
    }

    static SmsWakeProvider fromEnvironment() {
        String name = System.getenv("SMS_WAKE_PROVIDER");
        return name != null && "simulator".equalsIgnoreCase(name.trim()) ? new Simulator() : null;
    }

    /** Keeps the last messages in memory instead of sending them. */
    final class Simulator implements SmsWakeProvider {
        private final List<String[]> outbox = Collections.synchronizedList(new ArrayList<String[]>());

        @Override
        public String name() {
            return "simulador";
        }

        @Override
        public String send(String phone, String text) {
            if (phone == null || !phone.replaceAll("[\\s-]", "").matches("\\+?\\d{7,15}")) {
                return "número no válido";
            }
            outbox.add(new String[]{phone, text});
            while (outbox.size() > 50) outbox.remove(0);
            return null;
        }

        public List<String[]> outbox() {
            synchronized (outbox) {
                return new ArrayList<>(outbox);
            }
        }
    }
}
