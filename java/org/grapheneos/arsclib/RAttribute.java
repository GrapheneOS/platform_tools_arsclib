package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.CheckForNull;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

class RAttribute {
    static void toXml(Apk apk, Resources.Attribute attr, Element dst) {
        int formatFlags = attr.getFormatFlags();
        verify(formatFlags != 0);
        int rem = formatFlags;
        if (formatFlags != Resources.Attribute.FormatFlags.ANY_VALUE && formatFlags != Resources.Attribute.FormatFlags.ENUM_VALUE && formatFlags != Resources.Attribute.FormatFlags.FLAGS_VALUE) {
            var formatStr = new StringBuilder();
            for (var formatFlag : Resources.Attribute.FormatFlags.values()) {
                switch (formatFlag) {
                    case NONE, ENUM, FLAGS, UNRECOGNIZED: continue;
                }
                int val = formatFlag.getNumber();
                if ((rem & val) == val) {
                    if (!formatStr.isEmpty()) {
                        formatStr.append('|');
                    }
                    formatStr.append(formatFlag.name().toLowerCase(Locale.ROOT));
                    rem &= ~val;
                    if (rem == 0) {
                        break;
                    }
                }
            }
            // all other format flags should be handled by the loop above
            verify((rem & (Resources.Attribute.FormatFlags.ENUM_VALUE | Resources.Attribute.FormatFlags.FLAGS_VALUE)) == rem);
            if (!formatStr.isEmpty()) {
                dst.setAttribute("format", formatStr.toString());
            }
        }

        List<Resources.Attribute.Symbol> symbols = attr.getSymbolList();
        if (symbols.isEmpty()) {
            return;
        }
        verify((formatFlags & (Resources.Attribute.FormatFlags.ENUM_VALUE | Resources.Attribute.FormatFlags.FLAGS_VALUE)) != 0);
        String symType = null;
        if ((formatFlags & Resources.Attribute.FormatFlags.ENUM_VALUE) != 0) {
            verify((formatFlags & Resources.Attribute.FormatFlags.FLAGS_VALUE) == 0);
            symType = "enum";
        }
        if ((formatFlags & Resources.Attribute.FormatFlags.FLAGS_VALUE) != 0) {
            verify((formatFlags & Resources.Attribute.FormatFlags.ENUM_VALUE) == 0);
            symType = "flag";
        }
        verify(symType != null);
        for (Resources.Attribute.Symbol sym : symbols) {
            Element symElem = dst.getOwnerDocument().createElement(symType);
            symbolToXml(apk, sym, symElem);
            dst.appendChild(symElem);
        }
    }

    private static void symbolToXml(Apk apk, Resources.Attribute.Symbol sym, Element dst) {
        var name = RReference.asString(apk, sym.getName(), RReference.Context.ATTR_SYMBOL);
        verify(sym.getComment().isEmpty());
        dst.setAttribute("name", name);
        dst.setAttribute("value", Integer.toString(sym.getValue()));
    }

    static String convertAttrItem(Apk apk, Resources.Attribute attr, Resources.Item item) {
        int format = attr.getFormatFlags();
        int forbidden = Resources.Attribute.FormatFlags.ENUM_VALUE | Resources.Attribute.FormatFlags.FLAGS_VALUE;
        verify((format & forbidden) != forbidden);
        if ((format & Resources.Attribute.FormatFlags.ENUM_VALUE) != 0) {
            return convertEnumValue(apk, attr, item);
        }
        if ((format & Resources.Attribute.FormatFlags.FLAGS_VALUE) != 0) {
            return convertFlagsValue(apk, attr, item);
        }
        return RItem.asString(apk, item);
    }

    private static String convertEnumValue(Apk apk, Resources.Attribute attr, Resources.Item item) {
        List<Resources.Attribute.Symbol> symbols = attr.getSymbolList();

        if (item.getValueCase() == Resources.Item.ValueCase.REF) {
            return RReference.asString(apk, item.getRef(), RReference.Context.ATTR_SYMBOL);
        }

        verify(item.getValueCase() == Resources.Item.ValueCase.PRIM);
        Resources.Primitive prim = item.getPrim();
        int format = attr.getFormatFlags();
        if (!Primitives.isInt(prim)) {
            verify((format & Resources.Attribute.FormatFlags.ENUM_VALUE) != 0);
            verify(format != Resources.Attribute.FormatFlags.ENUM_VALUE);
            return Primitives.toString(prim);
        }
        int intVal = Primitives.getInt(prim);
        Resources.Attribute.Symbol symbol = null;
        for (Resources.Attribute.Symbol candidate : symbols) {
            if (candidate.getValue() == intVal) {
                symbol = candidate;
                break;
            }
        }
        if (symbol == null) {
            verify((format & Resources.Attribute.FormatFlags.INTEGER_VALUE) != 0);
            return Integer.toString(intVal);
        }
        RReference name = RReference.parse(apk, symbol.getName());
        verify(name != null);
        String res = name.entry().getName();
        if (res.equals("fill_parent")) {
            for (Resources.Attribute.Symbol candidate : symbols) {
                if (candidate.getValue() == symbol.getValue() && candidate.getName().getId() != symbol.getName().getId()) {
                    var alias = RReference.parse(apk, candidate.getName());
                    verify(alias != null);
                    res = alias.entry().getName();
                    break;
                }
            }
        }
        return res;
    }

    private static String convertFlagsValue(Apk apk, Resources.Attribute attr, Resources.Item item) {
        if (item.getValueCase() == Resources.Item.ValueCase.REF) {
            return RReference.asString(apk, item.getRef(), RReference.Context.ATTR_SYMBOL);
        }
        verify(item.getValueCase() == Resources.Item.ValueCase.PRIM);
        Resources.Primitive prim = item.getPrim();
        verify(Primitives.isInt(prim));
        int fullValue = Primitives.getInt(prim);

        int rem = fullValue;
        ArrayList<Resources.Attribute.Symbol> matches = new ArrayList<Resources.Attribute.Symbol>();
        for (Resources.Attribute.Symbol sym : attr.getSymbolList()) {
            int symVal = sym.getValue();
            if ((fullValue & symVal) != symVal) {
                continue;
            }
            matches.add(sym);
            rem &= ~symVal;
        }
        verify(!matches.isEmpty());
        verify(rem == 0);
        matches.sort((o1, o2) -> {
            int a = o1.getValue(), b = o2.getValue();
            int res = Integer.compare(Integer.bitCount(b), Integer.bitCount(a));
            if (res == 0) {
                res = Integer.compare(b, a);
            }
            return res;
        });

        // remove flags that are covered by other flags
        for (int i = 1; i < matches.size(); ++i) {
            int v = matches.get(i).getValue();
            for (int j = 0; j < i; ++j) {
                if ((matches.get(j).getValue() & v) == v) {
                    matches.remove(i);
                    //noinspection AssignmentToForLoopParameter
                    --i;
                    break;
                }
            }
        }

        if (matches.size() == 1) {
            return getSymbolName(apk, matches.getFirst());
        }

        var names = new ArrayList<String>(matches.size());
        for (Resources.Attribute.Symbol symbol : matches) {
            names.add(getSymbolName(apk, symbol));
        }
        Collections.sort(names);
        return String.join("|", names);
    }

    private static String getSymbolName(Apk apk, Resources.Attribute.Symbol sym) {
        return verifyNotNull(RReference.parse(apk, sym.getName())).entry().getName();
    }

    @CheckForNull
    static Resources.Attribute getById(Apk apk, ResourceId resId) {
        IndexedResourceTable resTable = apk.getResourceTable(resId.packageId());
        IndexedResourceType resType = resTable.getTypeById(resId.typeId());

        String typeName = resType.type.getName();
        verify(typeName.equals("attr") || typeName.equals(Constants.PRIVATE_ATTR_TYPE_NAME), typeName);
        Resources.Entry attrEntry = resType.getEntryById(resId.entryProtoId());
        if (attrEntry == null) {
            return null;
        }

        int configValCount = attrEntry.getConfigValueCount();
        verify(configValCount == 1);
        var cv = attrEntry.getConfigValue(0).getValue().getCompoundValue();
        verify(cv.getValueCase() == Resources.CompoundValue.ValueCase.ATTR);
        return cv.getAttr();
    }

    public static boolean isAndroidAttr(Resources.XmlAttribute attr) {
        return attr.getNamespaceUri().equals(Constants.ANDROID_NAMESPACE_URI);
    }
}
