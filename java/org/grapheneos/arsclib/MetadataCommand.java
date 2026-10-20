package org.grapheneos.arsclib;

import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources;
import com.google.gson.Gson;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.grapheneos.arsclib.Constants.ANDROID_NAMESPACE_URI;
import static org.grapheneos.arsclib.Constants.SELF_PACKAGE_ID;

/** Resolves manifest metadata resource references to ZIP entry paths, omitting absent keys. */
final class MetadataCommand {
    record Request(String aapt2Path, String apkPath, List<String> metadataNames) {}
    record Result(String packageName, String versionCode, Map<String, String> resourcePaths) {}

    static void run() throws Exception {
        var gson = new Gson();
        var request = gson.fromJson(new String(System.in.readAllBytes(), UTF_8), Request.class);
        Set<String> names = Set.copyOf(request.metadataNames());
        verify(!names.isEmpty() && names.stream().noneMatch(String::isEmpty),
                "metadataNames must contain nonempty names");

        Apk apk;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var zip = ResourceProcessor.convertToProtoApk(request.aapt2Path(),
                     Path.of(request.apkPath()))) {
            apk = ResourceProcessor.processProtoApk(zip, executor);
        }

        var paths = new LinkedHashMap<String, String>();
        collect(apk, apk.androidManifest.getElement(), names, paths);
        try (var zip = new ZipFile(request.apkPath())) {
            for (String path : paths.values()) {
                var entry = verifyNotNull(zip.getEntry(path), "missing APK entry: %s", path);
                verify(!entry.isDirectory(), "metadata refers to a directory: %s", path);
            }
        }
        var manifest = apk.androidManifest.getElement();
        var minor = verifyNotNull(attribute(manifest, "versionCode"), "missing versionCode");
        var major = attribute(manifest, "versionCodeMajor");
        long version = Integer.toUnsignedLong(Primitives.getInt(minor.getCompiledItem().getPrim()));
        if (major != null) {
            version |= (long) Primitives.getInt(major.getCompiledItem().getPrim()) << 32;
        }
        verify(version > 0, "invalid versionCode: %s", version);
        System.out.println(gson.toJson(new Result(apk.packageName, Long.toString(version), paths)));
    }

    private static void collect(Apk apk, Resources.XmlElement element, Set<String> names,
            Map<String, String> paths) {
        if (element.getName().equals("meta-data")) {
            var name = attribute(element, "name");
            if (name != null && names.contains(name.getValue())) {
                String key = name.getValue();
                verify(!paths.containsKey(key), "duplicate metadata: %s", key);
                var resource = verifyNotNull(attribute(element, "resource"),
                        "missing android:resource for %s", key);
                paths.put(key, resolvePath(apk, resource));
            }
        }
        for (var child : element.getChildList()) {
            if (child.hasElement()) {
                collect(apk, child.getElement(), names, paths);
            }
        }
    }

    private static Resources.XmlAttribute attribute(Resources.XmlElement element, String name) {
        for (var attribute : element.getAttributeList()) {
            if (attribute.getNamespaceUri().equals(ANDROID_NAMESPACE_URI)
                    && attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    private static String resolvePath(Apk apk, Resources.XmlAttribute attribute) {
        verify(attribute.hasCompiledItem() && attribute.getCompiledItem().hasRef(),
                "metadata value is not a resource reference");
        var reference = attribute.getCompiledItem().getRef();
        verify(reference.getType() == Resources.Reference.Type.REFERENCE,
                "metadata value is not a resource reference");
        verify(ResourceId.create(reference.getId()).packageId() == SELF_PACKAGE_ID,
                "metadata must reference a resource in this APK");
        var entry = verifyNotNull(RReference.parse(apk, reference)).entry();
        verify(entry.getConfigValueCount() == 1, "metadata resource has multiple configurations");
        var config = entry.getConfigValue(0);
        verify(config.getConfig().equals(Configuration.getDefaultInstance()),
                "metadata resource must have the default configuration");
        verify(config.getValue().hasItem() && config.getValue().getItem().hasFile(),
                "metadata resource is not a file");
        return config.getValue().getItem().getFile().getPath();
    }
}
