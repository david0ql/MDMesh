package com.hmdm.util;

import java.util.HashMap;
import java.util.Map;

/** Spanish names of the command types, for reports people read (the console keeps its own copy for the screen). */
public final class CommandLabels {

    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("device.lock", "Bloquear");
        LABELS.put("device.wipe", "Borrar dispositivo");
        LABELS.put("device.alert", "Mensaje");
        LABELS.put("device.ring", "Hacer sonar (alarma)");
        LABELS.put("device.ringStop", "Dejar de sonar");
        LABELS.put("device.lockscreenMessage", "Mensaje en pantalla de bloqueo");
        LABELS.put("device.passcodeReset", "Cambiar clave");
        LABELS.put("device.reboot", "Reiniciar");
        LABELS.put("kiosk.enter", "Entrar en quiosco");
        LABELS.put("kiosk.exit", "Salir del quiosco");
        LABELS.put("config.apply", "Aplicar política");
        LABELS.put("config.sync", "Sincronizar política");
        LABELS.put("policy.apply", "Aplicar restricción");
        LABELS.put("app.install", "Instalar app");
        LABELS.put("app.uninstall", "Desinstalar app");
        LABELS.put("apps.scan", "Escanear apps");
        LABELS.put("apps.icons", "Leer íconos");
        LABELS.put("device.powerMode", "Modo de conexión");
        LABELS.put("device.locationMode", "Modo de ubicación");
        LABELS.put("device.announce", "Anuncio");
        LABELS.put("device.systemUpdate", "Actualización de Android");
        LABELS.put("device.diagnose", "Diagnóstico");
        LABELS.put("device.silence", "Silenciar");
        LABELS.put("device.display", "Pantalla y letra");
        LABELS.put("device.appLaunch", "Abrir app");
        LABELS.put("device.openStore", "Abrir en Play Store");
        LABELS.put("device.storageScan", "Analizar almacenamiento");
        LABELS.put("device.storageClean", "Liberar espacio");
        LABELS.put("device.storageAccess", "Pedir acceso");
        LABELS.put("device.clearCache", "Borrar caché");
        LABELS.put("remote.vnc.start", "Iniciar remoto");
        LABELS.put("remote.vnc.stop", "Terminar remoto");
        LABELS.put("remote.inputSetup", "Activar control remoto");
        LABELS.put("device.lostMode", "Modo perdido");
    }

    private CommandLabels() {
    }

    /** The Spanish name of a command type, or the type itself when it has none. */
    public static String of(String type) {
        if (type == null) return "";
        String label = LABELS.get(type);
        return label == null ? type : label;
    }
}
