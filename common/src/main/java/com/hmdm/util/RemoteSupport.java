package com.hmdm.util;

/**
 * The remote-support app (droidVNC-NG, GPL-2.0, https://github.com/bk138/droidVNC-NG) this server hosts at
 * {@link #APK_PATH} for devices enrolled without USB remote support. Pinned: the agent refuses an APK whose sha256
 * differs. Keep in sync with scripts/fetch-droidvnc.sh / scripts/droidvnc-ng.sha256 (the deploy copies that APK).
 */
public final class RemoteSupport {
    public static final String PACKAGE = "net.christianbeier.droidvnc_ng";
    public static final String VERSION_NAME = "2.22.0";
    public static final int VERSION_CODE = 65;
    public static final String SHA256 = "02aa55aaa8b36d3e9bf9daffac1bd241e30aee4aed7068fd113b8f188f4cd1ac";
    public static final String APK_PATH = "/files/droidvnc-ng.apk";

    private RemoteSupport() {}
}
