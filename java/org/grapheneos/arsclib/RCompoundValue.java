package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import org.w3c.dom.Element;

import java.util.Locale;

import javax.annotation.CheckForNull;

import static com.google.common.base.Verify.verify;
import static org.grapheneos.arsclib.Constants.ATTR_TYPE_NAME;
import static org.grapheneos.arsclib.Constants.PRIVATE_ATTR_TYPE_NAME;

class RCompoundValue {

    static void toXml(Apk apk, Resources.CompoundValue cv, Element dst) {
        switch (cv.getValueCase()) {
            case ATTR -> { RAttribute.toXml(apk, cv.getAttr(), dst); return; }
            case STYLE -> { styleToXml(apk, cv.getStyle(), dst); return; }
            case ARRAY -> { arrayToXml(apk, cv.getArray(), dst); return; }
            case PLURAL -> { pluralToXml(apk, cv.getPlural(), dst); return; }
            // macros and styleables should never be present in compiled resource table
            case MACRO, STYLEABLE, VALUE_NOT_SET -> throw new IllegalArgumentException(cv.toString());
        }
        throw new IllegalArgumentException(cv.toString());
    }

    @CheckForNull
    static String asRawString(Apk apk, Resources.CompoundValue cv) {
        return switch (cv.getValueCase()) {
            case ATTR, STYLE -> null;
            case ARRAY -> arrayAsRawString(apk, cv.getArray());
            case PLURAL -> pluralAsRawString(apk, cv.getPlural());
            // macros and styleables should never be present in compiled resource table
            case MACRO, STYLEABLE, VALUE_NOT_SET -> throw new IllegalArgumentException(cv.toString());
        };
    }

    private static void arrayToXml(Apk apk, Resources.Array resArray, Element dst) {
        for (Resources.Array.Element element : resArray.getElementList()) {
            Element item = dst.getOwnerDocument().createElement("item");
            String text = RItem.asString(apk, element.getItem());
            if (!text.isEmpty() && element.getItem().getValueCase() == Resources.Item.ValueCase.STR) {
                boolean isNumber = true;
                for (int i = 0; i < text.length(); ++i) {
                    char c = text.charAt(i);
                    if ((c <= '9' && c >= '0')
                            || (c == '.' && i >= (text.charAt(0) == '-' ? 2 : 1))
                            || (c == '-' && i == 0)
                    ) {
                        continue;
                    }
                    isNumber = false;
                    break;
                }
                if (isNumber) {
                    text = '"' + text + '"';
                }
            }
            item.setTextContent(text);
            dst.appendChild(item);
        }
    }

    private static String arrayAsRawString(Apk apk, Resources.Array resArray) {
        int numElem = resArray.getElementCount();
        String[] arr = new String[numElem];
        for (int i = 0; i < numElem; ++i) {
            arr[i] = RItem.asString(apk, resArray.getElement(i).getItem());
        }
        return String.join(";", arr);
    }

    private static void pluralToXml(Apk apk, Resources.Plural plural, Element dst) {
        for (Resources.Plural.Entry entry : plural.getEntryList()) {
            Element item = dst.getOwnerDocument().createElement("item");
            item.setAttribute("quantity", entry.getArity().name().toLowerCase(Locale.ROOT));
            verify(entry.hasItem());
            item.setTextContent(RItem.asString(apk, entry.getItem()));
            dst.appendChild(item);
        }
    }

    private static String pluralAsRawString(Apk apk, Resources.Plural plural) {
        int entryNum = plural.getEntryCount();
        String[] arr = new String[entryNum];
        for (int i = 0; i < entryNum; ++i) {
            Resources.Plural.Entry entry = plural.getEntry(i);
            arr[i] = entry.getArity().name().toLowerCase(Locale.ROOT) + '=' + RItem.asString(apk, entry.getItem());
        }
        return String.join(";", arr);
    }

    private static void styleToXml(Apk apk, Resources.Style style, Element dst) {
        if (style.hasParent()) {
            dst.setAttribute("parent", RReference.asString(apk, style.getParent(), RReference.Context.OTHER));
        }
        for (Resources.Style.Entry entry : style.getEntryList()) {
            verify(entry.getComment().isEmpty());
            RReference key = RReference.parse(apk, entry.getKey());
            verify(key != null);
            verify(key.ref().getType() == Resources.Reference.Type.REFERENCE);

            String keyTypeName = key.type().getName();
            boolean isPrivate = keyTypeName.equals(PRIVATE_ATTR_TYPE_NAME);
            verify(isPrivate || keyTypeName.equals(ATTR_TYPE_NAME));

            verify(key.entry().getConfigValueCount() == 1);
            Resources.Value value = key.entry().getConfigValue(0).getValue();
            verify(value.getValueCase() == Resources.Value.ValueCase.COMPOUND_VALUE);
            Resources.CompoundValue cv = value.getCompoundValue();
            verify(cv.getValueCase() == Resources.CompoundValue.ValueCase.ATTR);
            Resources.Attribute attr = cv.getAttr();

            Element itemElement = dst.getOwnerDocument().createElement("item");
            itemElement.setAttribute("name", key.toString(apk, RReference.Context.OTHER));
            itemElement.setTextContent(RAttribute.convertAttrItem(apk, attr, entry.getItem()));
            dst.appendChild(itemElement);
        }
    }
}
