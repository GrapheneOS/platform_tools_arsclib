package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import javax.annotation.CheckForNull;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static org.grapheneos.arsclib.Constants.ANDROID_PACKAGE_ID;
import static org.grapheneos.arsclib.Constants.ANDROID_PACKAGE_NAME;
import static org.grapheneos.arsclib.Constants.ATTR_TYPE_NAME;
import static org.grapheneos.arsclib.Constants.PRIVATE_ATTR_TYPE_NAME;

record RReference(Resources.Reference ref, ResourceId resId,
                  Resources.Package pkg, Resources.Type type, Resources.Entry entry) {

    static String asString(Apk apk, Resources.Reference ref, Context context) {
        RReference rref = parse(apk, ref);
        if (rref == null) {
            return "@null";
        }
        return rref.toString(apk, context);
    }

    @CheckForNull
    static RReference parse(Apk apk, Resources.Reference ref) {
        if (ref.getId() == 0) {
            return null;
        }
        verify(!ref.getPrivate(), "private ref %s", ref);
        verify(!ref.getName().startsWith("@*"), "unexpected name: %s", ref);
        var resId = ResourceId.create(ref.getId());
        switch (ref.getType()) {
            case REFERENCE:
            case ATTRIBUTE:
                break;
            case UNRECOGNIZED:
            default:
                throw new IllegalArgumentException(ref.toString());
        }
        IndexedResourceTable resTable = apk.getResourceTable(resId.packageId());

        IndexedResourceType resType = resTable.getTypeById(resId.typeId());

        Resources.Entry e = verifyNotNull(resType.getEntryById(resId.entryProtoId()));

        Resources.Package pkg = resTable.table.getPackage(0);
        verify(pkg.getPackageId().getId() == resId.packageId(), "%s", resId);
        return new RReference(ref, resId, pkg, resType.type, e);
    }

    enum Context {
        ATTR_SYMBOL, OTHER,
    }

    String toString(Apk apk, Context ctx) {
        boolean isSamePackage = apk.packageName.equals(pkg.getPackageName());
        Resources.Visibility visibility = entry.getVisibility();
        Resources.Visibility.Level visibilityLevel = visibility.getLevel();
        verify(visibilityLevel != Resources.Visibility.Level.PRIVATE, "non-private visibility: %s", visibilityLevel);
        boolean isPrivate = !isSamePackage && visibilityLevel != Resources.Visibility.Level.PUBLIC;
        Resources.Reference.Type refType = ref.getType();

        String entryName = entry.getName();
        verify(!entryName.isEmpty());

        if (refType == Resources.Reference.Type.ATTRIBUTE) {
            if (type.getName().equals(PRIVATE_ATTR_TYPE_NAME) && !apk.packageName.equals(ANDROID_PACKAGE_NAME)) {
                verify(pkg.getPackageName().equals(ANDROID_PACKAGE_NAME));
                verify(pkg.getPackageId().getId() == ANDROID_PACKAGE_ID);
                return "?androidprv:attr/" + entryName;
            }
            String prefix = isSamePackage ? "?" : '?' + pkg.getPackageName() + ':';
            return prefix + "attr/" + entryName;
        } else {
            verify(refType == Resources.Reference.Type.REFERENCE);

            String prefix = isSamePackage ? "" : pkg.getPackageName() + ":";

            if (type.getName().equals(ATTR_TYPE_NAME)) {
                if (isSamePackage) {
                    return entryName;
                }
                return prefix + entryName;
            } else if (type.getName().equals(PRIVATE_ATTR_TYPE_NAME)) {
                if (isSamePackage) {
                    return entryName;
                }
                return "*" + prefix + entryName;
            } else if (isSamePackage && type.getName().equals("id")) {
                if (ctx == Context.ATTR_SYMBOL) {
                    return entryName;
                } else {
                    return "@+id/" + entryName;
                }
            } else {
                return "@" + (isPrivate ? "*" : "") + prefix + type.getName() + "/" + entryName;
            }
        }
    }
}
