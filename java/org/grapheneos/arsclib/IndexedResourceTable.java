package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import java.util.HashMap;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

class IndexedResourceTable {
    final Resources.ResourceTable table;
    private final HashMap<Integer, IndexedResourceType> index;

    IndexedResourceTable(Resources.ResourceTable resTable) {
        this.table = resTable;

        var index = new HashMap<Integer, IndexedResourceType>();

        int prevPackageId = 0;
        boolean hasPrevPackageId = false;

        for (Resources.Package resPackage : resTable.getPackageList()) {
            int packageId = resPackage.getPackageId().getId();
            if (resPackage.getPackageName().equals(Constants.ANDROID_PACKAGE_NAME)) {
                verify(packageId == Constants.ANDROID_PACKAGE_ID);
            } else {
                verify(packageId == Constants.SELF_PACKAGE_ID);
            }
            if (hasPrevPackageId) {
                verify(prevPackageId == packageId);
            } else {
                prevPackageId = packageId;
                hasPrevPackageId = true;
            }

            for (Resources.Type resType : resPackage.getTypeList()) {
                Integer typeId = Integer.valueOf(resType.getTypeId().getId());
                verify(index.put(typeId, new IndexedResourceType(resType)) == null);
            }
        }
        this.index = index;
    }

    IndexedResourceType getTypeById(int typeId) {
        return verifyNotNull(index.get(Integer.valueOf(typeId)));
    }
}
