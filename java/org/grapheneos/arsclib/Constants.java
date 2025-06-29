package org.grapheneos.arsclib;

import com.google.common.collect.ImmutableMap;

interface Constants {
    int ANDROID_PACKAGE_ID = 1;
    int SELF_PACKAGE_ID = 0x7f;
    String ANDROID_PACKAGE_NAME = "android";
    String ANDROID_MANIFEST_NAME = "AndroidManifest.xml";

    String ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android";

    String ATTR_TYPE_NAME = "attr";
    String PRIVATE_ATTR_TYPE_NAME = "^attr-private";

    String OVERLAY_RESOURCES_MAP_PREFIX = "overlay_resourcesMap_";

    ImmutableMap<String, String> PACKAGE_NAME_MAP = ImmutableMap.<String, String>builder()
            .put("com.google.android.apps.nexuslauncher", "com.android.launcher3")
            .put("com.google.android.avatarpicker", "com.android.avatarpicker")
            .put("com.google.android.captiveportallogin", "com.android.captiveportallogin")
            .put("com.google.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver")
            .put("com.google.android.cellbroadcastservice", "com.android.cellbroadcastservice")
            .put("com.google.android.connectivity.resources", "com.android.connectivity.resources")
            .put("com.google.android.devicelockcontroller", "com.android.devicelockcontroller")
            .put("com.google.android.documentsui", "com.android.documentsui")
            .put("com.google.android.healthconnect.controller", "com.android.healthconnect.controller")
            .put("com.google.android.nfc", "com.android.nfc")
            .put("com.google.android.providers.media.module", "com.android.providers.media.module")
            .put("com.google.android.networkstack", "com.android.networkstack")
            .put("com.google.android.networkstack.tethering", "com.android.networkstack.tethering")
            .put("com.google.android.permissioncontroller", "com.android.permissioncontroller")
            .put("com.google.android.storagemanager", "com.android.storagemanager")
            .put("com.google.android.uwb.resources", "com.android.uwb.resources")
            .put("com.google.android.apps.wallpaper", "com.android.wallpaper")
            .put("com.google.android.wifi.resources", "com.android.wifi.resources")
            .build();

}
