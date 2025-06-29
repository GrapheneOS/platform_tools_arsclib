package org.grapheneos.arsclib;

import com.android.aapt.Resources;

record ResourceId(int packageId, int typeId, int entryId) {

    static ResourceId create(int id) {
        int packageId = id >>> 24;
        int typeId = (id >>> 16) & 0xff;
        int entryId = id & 0xffff;
        return new ResourceId(packageId, typeId, entryId);
    }

    Resources.EntryId entryProtoId() {
        return Resources.EntryId.newBuilder().setId(entryId).build();
    }
}
