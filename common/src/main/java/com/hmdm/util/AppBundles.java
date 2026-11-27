package com.hmdm.util;

import java.io.File;
import java.util.zip.ZipFile;

/**
 * Android App Bundles (.aab). An .aab is what a developer hands to Google Play, not something a phone can install:
 * Play builds the APKs from it and signs them with the key it keeps. Building them here (bundletool) would mean signing
 * with a key of ours, so the result would carry another signature than every copy of the app already on the phones
 * (Android then refuses the update, INSTALL_FAILED_UPDATE_INCOMPATIBLE) and would need a Java tool plus a native
 * aapt2 for the server's processor inside the image. So an .aab is refused with the way to turn it into something
 * installable, signed by its owner — the .apks that comes out is accepted as is.
 */
public final class AppBundles {

    private AppBundles() {}

    public static final String AAB_REJECTED =
            "Un .aab (Android App Bundle) no se puede instalar en un teléfono: es el formato que se entrega a Google Play, "
            + "sin firma de distribución. Conviértelo con tu propia firma y sube el resultado: "
            + "«bundletool build-apks --mode=universal --bundle=app.aab --output=app.apks --ks=tu.keystore --ks-key-alias=tu_alias» "
            + "y sube app.apks aquí (o pide a tu desarrollador el APK firmado: en Android Studio, Build > Generate Signed App Bundle / APK > APK). "
            + "Usa siempre el mismo keystore: si la firma cambia, los teléfonos rechazan la actualización.";

    public static boolean isAabName(String fileName) {
        return fileName != null && fileName.trim().toLowerCase().endsWith(".aab");
    }

    /** True when the zip is an App Bundle whatever its name: it carries a BundleConfig.pb and its base module's manifest. */
    public static boolean isAab(File zip) {
        try (ZipFile zf = new ZipFile(zip)) {
            return zf.getEntry("BundleConfig.pb") != null && zf.getEntry("base/manifest/AndroidManifest.xml") != null;
        } catch (Exception e) {
            return false;
        }
    }
}
