package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import java.util.HashMap;

import javax.annotation.CheckForNull;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

class IndexedResourceType {
    final Resources.Type type;
    private final HashMap<Resources.EntryId, Resources.Entry> index;

    IndexedResourceType(Resources.Type type) {
        this.type = type;
        var index = new HashMap<Resources.EntryId, Resources.Entry>(type.getEntryCount());
        for (Resources.Entry e : type.getEntryList()) {
            verify(index.put(e.getEntryId(), e) == null);
        }
        this.index = index;
    }

    @CheckForNull
    Resources.Entry getEntryById(Resources.EntryId entryId) {
        return index.get(entryId);
    }
}
