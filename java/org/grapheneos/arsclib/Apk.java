package org.grapheneos.arsclib;

import com.android.aapt.Resources;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;

import javax.annotation.CheckForNull;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

public class Apk {
    final IndexedResourceTable selfResourceTable;
    final Resources.XmlNode androidManifest;
    final String packageName;
    final HashMap<String, FileContents> files;
    String fileName;
    Path path;
    ResourceProcessor resourceProcessor;

    HashMap<String, OverlayInfo> overlayInfos = new HashMap<>();

    HashSet<String> resourceMapFiles = new HashSet<>();

    public static class FileContents {
        public final byte[] contents;

        FileContents(byte[] contents) {
            this.contents = contents;
        }

        private volatile String sha256;

        public String getSha256() {
            String cache = sha256;
            if (cache != null) {
                return cache;
            }

            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            byte[] sha256b = md.digest(contents);
            return sha256 = HexFormat.of().formatHex(sha256b);
        }
    }

    Apk(IndexedResourceTable selfResourceTable, Resources.XmlNode androidManifest, HashMap<String, FileContents> files) {
        this.selfResourceTable = selfResourceTable;
        this.androidManifest = androidManifest;
        this.files = files;
        String pkgName = null;
        for (Resources.XmlAttribute attr : androidManifest.getElement().getAttributeList()) {
            if ("package".equals(attr.getName())) {
                pkgName = attr.getValue();
                break;
            }
        }
        verify(pkgName != null);
        this.packageName = pkgName;
    }

    void maybeParseOverlayInfo() {
        boolean hasNamelessOverlay = false;
        for (Resources.XmlNode child : androidManifest.getElement().getChildList()) {
            if (!child.hasElement()) {
                continue;
            }
            if (!"overlay".equals(child.getElement().getName())) {
                continue;
            }
            String name = null;
            String targetPackage = null;
            String targetName = null;
            ResourcesMap resourcesMap = null;
            for (Resources.XmlAttribute attr : child.getElement().getAttributeList()) {
                if (!Constants.ANDROID_NAMESPACE_URI.equals(attr.getNamespaceUri())) {
                    continue;
                }
                switch (attr.getName()) {
                    case "name" -> name = attr.getValue();
                    case "targetPackage" -> targetPackage = attr.getValue();
                    case "targetName" -> targetName = attr.getValue();
                    case "resourcesMap" -> resourcesMap = readResourceMap(attr);
                }
            }
            verify(targetPackage != null);
            String translatedTargetPackage = Constants.PACKAGE_NAME_MAP.get(targetPackage);
            if (translatedTargetPackage != null) {
                targetPackage = translatedTargetPackage;
            }
            var info = new OverlayInfo(targetPackage, targetName, resourcesMap);
            if (name == null) {
                if (!overlayInfos.isEmpty()) {
                    verify(overlayInfos.size() == 1);
                    verify(overlayInfos.get("").equals(info));
                    continue;
                }
                name = "";
                hasNamelessOverlay = true;
            } else {
                verify(!hasNamelessOverlay);
            }

            verify(overlayInfos.put(name, info) == null);
        }
    }

    record OverlayInfo(String targetPackage, @CheckForNull String targetName, @CheckForNull ResourcesMap resourcesMap) {}

    record ResourcesMap(HashMap<String, Resources.XmlAttribute> map) {}

    private ResourcesMap readResourceMap(Resources.XmlAttribute attr) {
        verify(attr.hasCompiledItem());
        Resources.Item item = attr.getCompiledItem();
        verify(item.getValueCase() == Resources.Item.ValueCase.REF);
        Resources.Reference ref = item.getRef();
        verify(ref.getType() == Resources.Reference.Type.REFERENCE);
        RReference rref = RReference.parse(this, ref);
        verify(rref != null);
        verify(rref.type().getName().equals("xml"));
        Resources.Entry fileEntry = rref.entry();
        verify(fileEntry.getConfigValueCount() == 1);
        Resources.Value value = fileEntry.getConfigValue(0).getValue();
        verify(value.getValueCase() == Resources.Value.ValueCase.ITEM);
        Resources.Item valueItem = value.getItem();
        verify(valueItem.getValueCase() == Resources.Item.ValueCase.FILE);
        Resources.FileReference fileRef = valueItem.getFile();
        verify(fileRef.getType() == Resources.FileReference.Type.PROTO_XML);
        String filePath = fileRef.getPath();

        // intentionally don't check return value, same resourceMap can be used by multiple overlays
        resourceMapFiles.add(filePath);

        FileContents protoXmlBytes = verifyNotNull(this.files.get(filePath), filePath);
        Resources.XmlNode rootNode;
        try {
            rootNode = Resources.XmlNode.parseFrom(protoXmlBytes.contents);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        verify(rootNode.getElement().getName().equals("overlay"));
        HashMap<String, Resources.XmlAttribute> map = new HashMap<>();
        for (Resources.XmlNode overlayNode : rootNode.getElement().getChildList()) {
            verify(overlayNode.hasElement());
            Resources.XmlElement overlayElem = overlayNode.getElement();
            verify(overlayElem.getName().equals("item"));
            verify(overlayElem.getChildCount() == 0);
            verify(overlayElem.getAttributeCount() == 2);
            String target = null;
            Resources.XmlAttribute value_ = null;
            for (Resources.XmlAttribute overlayAttr : overlayElem.getAttributeList()) {
                verify(overlayAttr.getNamespaceUri().isEmpty());
                switch (overlayAttr.getName()) {
                    case "target":
                        target = overlayAttr.getValue();
                        verify(!target.isEmpty());
                        break;
                    case "value":
                        value_ = overlayAttr;

                }
            }
            verify(target != null);
            verify(value_ != null);
            verify(map.put(target, value_) == null);
        }
        return new ResourcesMap(map);
    }

    IndexedResourceTable getResourceTable(int packageId) {
        if (packageId == Constants.ANDROID_PACKAGE_ID) {
            return resourceProcessor.frameworkApk().selfResourceTable;
        }

        verify(packageId == Constants.SELF_PACKAGE_ID, "unexpected packageId: %s", packageId);
        return selfResourceTable;
    }
}
