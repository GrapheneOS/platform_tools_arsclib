package org.grapheneos.arsclib;

import com.android.aapt.Resources;
import com.google.protobuf.InvalidProtocolBufferException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.CheckForNull;

import static com.android.aapt.ConfigurationOuterClass.*;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.io.Files.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;
import static org.grapheneos.arsclib.Constants.ANDROID_MANIFEST_NAME;
import static org.grapheneos.arsclib.Constants.ANDROID_NAMESPACE_URI;
import static org.grapheneos.arsclib.Constants.SELF_PACKAGE_ID;
import static org.grapheneos.arsclib.RAttribute.isAndroidAttr;
import static org.grapheneos.arsclib.Utils.escapeYamlListItem;

public class ApkConverter {

    private final Apk apk;
    private Filters exclusionFilter = new Filters();
    private Filters inclusionFilter = new Filters();

    // path -> file contents
    private final HashMap<String, String> generatedResourceMaps = new HashMap<>();
    private AtomicInteger resourceMapIndex = new AtomicInteger(1);

    private final Set<REntry> entriesToInclude = new HashSet<>();
    private final Set<REntry> fileEntriesToInclude = new HashSet<>();

    private ApkConverter(Apk apk) {
        this.apk = apk;
    }

    public static String convert(Apk apk, ConversionCommand cmd, Path destination) {
        var c = new ApkConverter(apk);
        c.exclusionFilter = cmd.exclusionFilters;
        c.inclusionFilter = cmd.inclusionFilters;
        try {
            return c.convertEntries(destination);
        } catch (Exception e) {
            throw Utils.asRuntimeException(e);
        }
    }

    record RConfigValue(Resources.Entry entry, Resources.ConfigValue configValue) {
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RConfigValue o) {
                return configValue == o.configValue && entry == o.entry;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return entry.getEntryId().getId() ^ configValue.getConfig().getStringified().hashCode();
        }
    }

    static String normalizedTypeName(Resources.Type type) {
        String name = type.getName();
        if (name.equals(Constants.PRIVATE_ATTR_TYPE_NAME)) {
            return Constants.ATTR_TYPE_NAME;
        }
        return name;
    }

    record REntry(Resources.Type type, RConfigValue entry, String normalizedTypeName) {

        static REntry create(Resources.Type type, Resources.Entry entry, Resources.ConfigValue configValue) {
            return new REntry(type, new RConfigValue(entry, configValue),
                    ApkConverter.normalizedTypeName(type));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof REntry o) {
                return entry.equals(o.entry) && type == o.type;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (type.getTypeId().getId() << 16) ^ entry.hashCode();
        }
    }

    @CheckForNull
    String convertEntries(Path destination) throws Exception {
        Resources.XmlNode androidManifest = processManifest();
        if (androidManifest == null) {
            return null;
        }

        Path dir = destination.resolve(apk.fileName.substring(0, apk.fileName.length() - ".apk".length()));
        Files.createDirectories(dir);

        String manifestString = RXmlConverter.convert(androidManifest, apk);
        Files.writeString(dir.resolve(ANDROID_MANIFEST_NAME), manifestString, CREATE_NEW);

        HashMap<String, /* elements */ ArrayList<REntry>> elementsByConfig = new HashMap<>();
        for (REntry centry : entriesToInclude) {
            var cv = centry.entry.configValue;
            Configuration config = cv.getConfig();

            ArrayList<REntry> elems = elementsByConfig.computeIfAbsent(config.getStringified(), k -> new ArrayList<>());
            elems.add(centry);
        }

        Path resDir = Files.createDirectory(dir.resolve("res"));

        for (Map.Entry<String, ArrayList<REntry>> e : elementsByConfig.entrySet()) {
            Document doc = documentFromEntries(e.getValue());
            String config = e.getKey();
            String name = config.isEmpty() ? "values" : "values-" + config;
            Path subDir = Files.createDirectory(resDir.resolve(name));
            Files.writeString(subDir.resolve("values.xml"), XmlUtils.format(doc), CREATE_NEW);
        }

        writeFiles(fileEntriesToInclude, resDir);

        String partition = apk.resourceProcessor.rootPath().relativize(apk.path).getName(0).toString();
        String partitionBp = switch (partition) {
            case "product" -> "product";
            case "vendor" -> "soc";
            default -> throw new IllegalStateException(apk.path.toString());
        };

        String moduleName = getNameWithoutExtension(apk.path.toString());
        String androidBp = "runtime_resource_overlay { name: \"" + moduleName + "\", "
                + partitionBp + "_specific: true, }";
        Files.writeString(dir.resolve("Android.bp"), androidBp);

        return moduleName;
    }

    private Document documentFromEntries(List<REntry> entries) {
        entries.sort(Comparator.comparing((REntry e) -> e.normalizedTypeName)
                .thenComparing(e -> e.entry.entry.getName()));
        Document doc = XmlUtils.createDocument();
        Element rootElem = doc.createElement("resources");
        doc.appendChild(rootElem);

        entries.stream().parallel().map((REntry rentry) -> {
            Element valueElem = doc.createElement(rentry.normalizedTypeName);
            valueElem.setAttribute("name", rentry.entry.entry.getName());
            Resources.Value value = rentry.entry.configValue.getValue();
            switch (value.getValueCase()) {
                case ITEM -> {
                    Resources.Item item = value.getItem();
                    valueElem.setTextContent(RItem.asString(apk, item));
                    if (item.getValueCase() == Resources.Item.ValueCase.STR) {
                        if (RItem.shouldMarkAsNonFormatted(item.getStr().getValue())) {
                            valueElem.setAttribute("formatted", "false");
                        }
                    }
                }
                case COMPOUND_VALUE -> RCompoundValue.toXml(apk, value.getCompoundValue(), valueElem);
                default -> throw new RuntimeException(value.toString());
            }
            return valueElem;
        }).forEachOrdered(rootElem::appendChild);

        return doc;
    }

    private String valueAsRawString(Resources.Value value) {
        return switch (value.getValueCase()) {
            case ITEM -> RItem.asRawString(apk, value.getItem());
            case COMPOUND_VALUE -> RCompoundValue.asRawString(apk, value.getCompoundValue());
            default -> throw new RuntimeException(value.toString());
        };
    }

    private void writeFiles(Collection<REntry> files, Path resDir) throws IOException, InterruptedException, ExecutionException {
        var tasks = new ArrayList<Callable<Void>>();

        for (REntry entry : files) {
            String typeName = entry.type.getName();
            String config = entry.entry.configValue.getConfig().getStringified();
            String dirName = config.isEmpty() ? typeName : typeName + "-" + config;

            Resources.FileReference fileRef = entry.entry.configValue.getValue().getItem().getFile();
            String origFilePath = fileRef.getPath();

            String resName = entry.entry.entry.getName();
            String origFileName = Path.of(origFilePath).getFileName().toString();
            verify(origFileName.startsWith(resName));
            verify(!origFileName.startsWith(Constants.OVERLAY_RESOURCES_MAP_PREFIX));

            Apk.FileContents bytes = verifyNotNull(apk.files.get(origFilePath), origFilePath);
            byte[] contents = bytes.contents;
            switch (fileRef.getType()) {
                case UNKNOWN:
                case PNG:
                    break;
                default: {
                    verify(fileRef.getType() == Resources.FileReference.Type.PROTO_XML, "%s", fileRef);
                    contents = RXmlConverter.convert(Resources.XmlNode.parseFrom(contents), apk).getBytes(UTF_8);
                }
            }

            byte[] contents_ = contents;

            tasks.add(() -> {
                Path dir = resDir.resolve(dirName);
                Files.createDirectories(dir);
                var dstFile = dir.resolve(origFileName);
                Files.write(dstFile, contents_, CREATE_NEW);
                return null;
            });
        }

        Path xmlDir = resDir.resolve("xml");
        if (!generatedResourceMaps.isEmpty()) {
            Files.createDirectory(xmlDir);
        }

        for (var e : generatedResourceMaps.entrySet()) {
            String fileName = e.getKey();
            String contents = e.getValue();
            tasks.add(() -> {
                Path dstFile = xmlDir.resolve(fileName + ".xml");
                Files.writeString(dstFile, contents, CREATE_NEW);
                return null;
            });
        }

        for (Future<Void> f : apk.resourceProcessor.executor().invokeAll(tasks)) {
            f.get();
        }
    }

    @CheckForNull
    private Resources.XmlNode processManifest() {
        Resources.XmlNode.Builder manifestBuilder = apk.androidManifest.toBuilder();
        Resources.XmlElement.Builder rootBuilder = manifestBuilder.getElement().toBuilder();
        verify(rootBuilder.getName().equals("manifest"));

        filterRootManifestAttrs(rootBuilder);

        List<Resources.XmlNode> rootChildren = rootBuilder.getChildList();
        rootBuilder.clearChild();

        boolean hasOverlay = false;

        for (Resources.XmlNode child : rootChildren) {
            if (child.getNodeCase() != Resources.XmlNode.NodeCase.ELEMENT) {
                rootBuilder.addChild(child);
                continue;
            }
            Resources.XmlElement elem = child.getElement();
            switch (elem.getName()) {
                case "uses-sdk":
                    // auto-generated
                    continue;
                case "overlay":
                    Resources.XmlElement overlay = processOverlay(elem);
                    if (overlay != null) {
                        rootBuilder.addChild(child.toBuilder().setElement(overlay).build());
                        hasOverlay = true;
                    }
                    continue;
            }
            rootBuilder.addChild(child);
        }
        if (!hasOverlay) {
            return null;
        }
        manifestBuilder.setElement(rootBuilder.build());
        return manifestBuilder.build();
    }

    @CheckForNull
    private static Resources.XmlAttribute maybeTranslateTargetPackage(Resources.XmlAttribute attr) {
        String original = attr.getValue();
        String translated = Constants.PACKAGE_NAME_MAP.get(original);
        if (translated == null) {
            return null;
        }
        return attr.toBuilder().setValue(translated).build();
    }

    @CheckForNull
    private Resources.XmlElement processOverlay(Resources.XmlElement overlayElem) {
        Resources.XmlElement.Builder overlayElemB = overlayElem.toBuilder();

        List<Resources.XmlAttribute> attrs = overlayElemB.getAttributeList();
        overlayElemB.clearAttribute();

        Resources.XmlAttribute resourcesMap = null;
        String targetPackage = null;
        String targetName = null;

        for (Resources.XmlAttribute attr : attrs) {
            switch (attr.getName()) {
                case "resourcesMap":
                    verify(isAndroidAttr(attr));
                    resourcesMap = attr;
                    continue;
                case "targetName":
                    verify(isAndroidAttr(attr));
                    targetName = attr.getValue();
                    break;
                case "targetPackage":
                    verify(isAndroidAttr(attr));
                    Resources.XmlAttribute translated = maybeTranslateTargetPackage(attr);
                    if (translated != null) {
                        targetPackage = translated.getValue();
                        overlayElemB.addAttribute(translated);
                        continue;
                    }
                    targetPackage = attr.getValue();
                    break;
            }
            overlayElemB.addAttribute(attr);
        }

        verify(targetPackage != null);
        var target = new OverlayTarget(targetPackage, targetName);

        if (resourcesMap != null) {
            Resources.XmlAttribute processedResourcesMap = processResourcesMap(resourcesMap, target);
            if (processedResourcesMap == null) {
                return null;
            }
            overlayElemB.addAttribute(processedResourcesMap);
        } else {
            if (!processLegacyOverlay(target)) {
                return null;
            }
        }
        return overlayElemB.build();
    }

    private static boolean shouldSkipConfig(Configuration config) {
        switch (config.getLocale()) {
            case "ar-XB":
            case "en-XA":
                // ignore pseudo-locales: https://developer.android.com/guide/topics/resources/pseudolocales
                return true;
        }
        return false;
    }

    private boolean processLegacyOverlay(OverlayTarget target) {
        Resources.ResourceTable table = apk.selfResourceTable.table;
        if (table.getPackageCount() == 0) {
            return false;
        }

        boolean includedAny = false;

        for (Resources.Package pkg : table.getPackageList()) {
            verify(pkg.getPackageId().getId() == Constants.SELF_PACKAGE_ID ||
                pkg.getPackageName().equals(Constants.ANDROID_PACKAGE_NAME) && pkg.getPackageId().getId() == Constants.ANDROID_PACKAGE_ID);

            for (Resources.Type type : pkg.getTypeList()) {
                if (type.getName().equals("id")) {
                    // resource IDs are auto-generated
                    continue;
                }

                String typeStem = target.createSpecStem(normalizedTypeName(type));

                for (Resources.Entry entry : type.getEntryList()) {
                    String baseSpec = typeStem + "/" + entry.getName();
                    includedAny |= processConfigValues(baseSpec, type, entry);
                }

            }
        }

        return includedAny;
    }

    record OverlayTarget(String targetPackage, @CheckForNull String targetName) {

        String createSpecStem(@CheckForNull String type) {
            var b = new StringBuilder();
            b.append(targetPackage);
            if (targetName != null) {
                b.append('/').append(targetName);
            }
            if (type != null) {
                b.append(':').append(type);
            }
            return b.toString();
        }
    }

    private Resources.XmlNode getResourceMapFile(Resources.XmlAttribute attr) {
        verify(attr.hasCompiledItem());
        Resources.Item item = attr.getCompiledItem();

        verify(item.hasRef());
        Resources.Reference ref = item.getRef();
        verify(ref.getType() == Resources.Reference.Type.REFERENCE);

        RReference rref = RReference.parse(apk, ref);
        verify(rref != null);
        verify(rref.type().getName().equals("xml"));

        Resources.Entry fileEntry = rref.entry();
        verify(fileEntry.getConfigValueCount() == 1);

        Resources.Value value = fileEntry.getConfigValue(0).getValue();
        verify(value.hasItem());

        Resources.Item valueItem = value.getItem();
        verify(valueItem.hasFile());

        Resources.FileReference fileRef = valueItem.getFile();
        verify(fileRef.getType() == Resources.FileReference.Type.PROTO_XML);
        String filePath = fileRef.getPath();

        Apk.FileContents protoXml = verifyNotNull(apk.files.get(filePath), filePath);

        Resources.XmlNode rootNode;
        try {
            rootNode = Resources.XmlNode.parseFrom(protoXml.contents);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        return rootNode;
    }

    private Resources.XmlAttribute processResourcesMap(Resources.XmlAttribute attr, OverlayTarget overlayTarget) {
        Resources.XmlNode rootNode = getResourceMapFile(attr);

        verify(rootNode.hasElement());
        Resources.XmlElement rootElem = rootNode.getElement();
        verify(rootElem.getName().equals("overlay"));
        verify(rootElem.getAttributeCount() == 0);

        Resources.XmlElement.Builder rootElemB = rootElem.toBuilder();

        List<Resources.XmlNode> overlayItems = rootElemB.getChildList();
        rootElemB.clearChild();

        var resourceSpec = new StringBuilder();
        resourceSpec.append(overlayTarget.targetPackage);
        if (overlayTarget.targetName != null) {
            resourceSpec.append('/').append(overlayTarget.targetName);
        }
        resourceSpec.append(':');
        String specPrefix = resourceSpec.toString();

        for (Resources.XmlNode overlayNode : overlayItems) {
            verify(overlayNode.hasElement());
            Resources.XmlElement overlayItem = overlayNode.getElement();
            verify(overlayItem.getName().equals("item"));
            verify(overlayItem.getChildCount() == 0);
            verify(overlayItem.getAttributeCount() == 2);
            String target = null;
            Resources.XmlAttribute value = null;
            for (Resources.XmlAttribute overlayAttr : overlayItem.getAttributeList()) {
                verify(overlayAttr.getNamespaceUri().isEmpty());
                verify(overlayAttr.getResourceId() == 0);
                switch (overlayAttr.getName()) {
                    case "target" -> {
                        verify(!overlayAttr.hasCompiledItem());
                        target = overlayAttr.getValue();
                    }
                    case "value" -> value = overlayAttr;
                }
            }
            verify(target != null);
            verify(value != null);

            String[] targetParts = target.split("/");
            verify(targetParts.length == 2);
            String targetType = targetParts[0];
            verify(!targetType.isEmpty());
            String targetName = targetParts[1];
            verify(!targetName.isEmpty());

            boolean include;

            if (value.hasCompiledItem()) {
                Resources.Item ci = value.getCompiledItem();
                if (ci.hasRef()) {
                    RReference ref = RReference.parse(apk, ci.getRef());
                    if (ref != null) {
                        verify(normalizedTypeName(ref.type()).equals(targetType));
                        if (ref.pkg().getPackageId().getId() == SELF_PACKAGE_ID) {
                            String specBase = specPrefix + target;
                            include = processConfigValues(specBase, ref.type(), ref.entry());
                        } else {
                            include = shouldIncludePrecise(specPrefix + target + " = " + ref.toString(apk, RReference.Context.OTHER));
                        }
                    } else {
                        include = shouldIncludePrecise(specPrefix + target + " = @null");
                    }
                } else {
                    String spec = specPrefix + target + " = " + RItem.asRawString(apk, ci);
                    include = shouldIncludePrecise(spec);
                }
            } else {
                include = shouldIncludePrecise(specPrefix + target + " = " + value.getValue());
            }
            if (include) {
                rootElemB.addChild(overlayNode);
            }
        }

        if (rootElemB.getChildCount() == 0) {
            return null;
        }

        Resources.XmlNode newMap = rootNode.toBuilder().setElement(rootElemB.build()).build();
        String newMapText = RXmlConverter.convert(newMap, apk);

        String resourceMapName = Constants.OVERLAY_RESOURCES_MAP_PREFIX + resourceMapIndex.getAndIncrement();
        generatedResourceMaps.put(resourceMapName, newMapText);

        return attr.toBuilder()
                .clearCompiledItem()
                .clearResourceId()
                .setValue("@xml/" + resourceMapName)
                .build();
    }

    enum IncludeAllCheckResult {
        INCLUDE_ALL, EXCLUDE_ALL, CHECK_SPECIFIC;
    }

    private boolean processConfigValues(String specBase, Resources.Type type, Resources.Entry entry) {
        if (entry.getConfigValueCount() == 1) {
            Resources.ConfigValue cv = entry.getConfigValue(0);
            if (cv.hasValue()) {
                Resources.Value v = cv.getValue();
                if (v.hasItem()) {
                    Resources.Item i = v.getItem();
                    if (i.hasFile() && apk.resourceMapFiles.contains(i.getFile().getPath())) {
                        return false;
                    }
                }
            }
        }

        var includeAllRes = shouldIncludeAll(specBase + "|*");
        if (includeAllRes == IncludeAllCheckResult.EXCLUDE_ALL) {
            return false;
        }

        boolean includedAny = false;

        for (Resources.ConfigValue cv : entry.getConfigValueList()) {
            if (shouldSkipConfig(cv.getConfig())) {
                continue;
            }

            if (includeAllRes == IncludeAllCheckResult.INCLUDE_ALL || shouldInclude(specBase, cv)) {
                includedAny = true;
                var rentry = REntry.create(type, entry, cv);
                Resources.Value val = cv.getValue();
                if (val.hasItem() && val.getItem().hasFile()) {
                    Resources.FileReference fileRef = val.getItem().getFile();
                    String fileName = Path.of(fileRef.getPath()).getFileName().toString();
                    verify(!fileName.isEmpty(), "%s", fileRef);
                    verify(!fileName.startsWith(Constants.OVERLAY_RESOURCES_MAP_PREFIX), "reserved file name: %s", fileRef);
                    fileEntriesToInclude.add(rentry);
                } else {
                    entriesToInclude.add(rentry);
                }
            }
        }
        return includedAny;
    }

    private IncludeAllCheckResult shouldIncludeAll(String spec) {
        verify(spec.endsWith("|*"));
        if (exclusionFilter.test(spec)) {
            return IncludeAllCheckResult.EXCLUDE_ALL;
        }
        if (inclusionFilter.test(spec)) {
            return IncludeAllCheckResult.INCLUDE_ALL;
        }
        return IncludeAllCheckResult.CHECK_SPECIFIC;
    }

    private boolean shouldInclude(String baseSpec, Resources.ConfigValue configValue) {
        var b = new StringBuilder();
        b.append(baseSpec);
        String config = configValue.getConfig().getStringified();
        if (!config.isEmpty()) {
            b.append('|').append(config);
        }
        b.append(" = ");
        Resources.Value val = configValue.getValue();
        if (val.hasItem() && val.getItem().hasFile()) {
            b.append(apk.files.get(val.getItem().getFile().getPath()).getSha256());
        } else {
            b.append(valueAsRawString(val));
        }
        String s = b.toString();

        if (exclusionFilter.test(s)) {
            return false;
        }
        if (inclusionFilter.test(s)) {
            return true;
        }
        boolean includeByDefault = true;
        if (includeByDefault) {
            System.out.println("including unknown entry: " + escapeYamlListItem(s));
        }
        return includeByDefault;
    }

    private boolean shouldIncludePrecise(String spec) {
        if (exclusionFilter.test(spec)) {
            return false;
        }
        if (inclusionFilter.test(spec)) {
            return true;
        }
        boolean includeByDefault = true;
        if (includeByDefault) {
            System.out.println("including unknown entry: " + escapeYamlListItem(spec));
        }
        return includeByDefault;
    }

    private static void filterRootManifestAttrs(Resources.XmlElement.Builder rootBuilder) {
        List<Resources.XmlAttribute> attrList = rootBuilder.getAttributeList();
        rootBuilder.clearAttribute();
        for (Resources.XmlAttribute attr : attrList) {
            switch (attr.getName()) {
                case "compileSdkVersion":
                case "compileSdkVersionCodename":
                    verify(attr.getNamespaceUri().equals(ANDROID_NAMESPACE_URI));
                    continue;
                case "platformBuildVersionCode":
                case "platformBuildVersionName":
                    verify(attr.getNamespaceUri().isEmpty());
                    continue;
            }
            rootBuilder.addAttribute(attr);
        }
    }
}
