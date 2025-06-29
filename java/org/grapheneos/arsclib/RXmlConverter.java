package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.HashMap;
import java.util.HashSet;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

class RXmlConverter {

    static String convert(Resources.XmlNode rNode, Apk apk) {
        HashMap<String, String> namespaces = new HashMap<>();
        verify(rNode.getNodeCase() == Resources.XmlNode.NodeCase.ELEMENT);
        Document doc = XmlUtils.createDocument();
        convertElement(namespaces, rNode.getElement(), doc, apk);
        return XmlUtils.format(doc);
    }

    private static String getQualifiedName(String namespace, HashMap<String, String> namespaces, String name) {
        String prefix = verifyNotNull(namespaces.get(namespace), "%s", namespace);
        return prefix + ':' + name;
    }

    private static void convertElement(HashMap<String, String> namespaces, Resources.XmlElement rElement, Node parent, Apk apk) {
        Document doc = parent instanceof Document ? (Document) parent : parent.getOwnerDocument();
        Element element;
        {
            String ns = rElement.getNamespaceUri();
            String name = rElement.getName();
            element = ns.isEmpty() ?
                    doc.createElement(name) :
                    doc.createElementNS(ns, getQualifiedName(ns, namespaces, name));
        }
        if (!rElement.getNamespaceDeclarationList().isEmpty()) {
            namespaces = new HashMap<>(namespaces);
            HashSet<String> seenUris = new HashSet<>(rElement.getNamespaceDeclarationCount());
            for (var ns : rElement.getNamespaceDeclarationList()) {
                String prefix = ns.getPrefix();
                if (!seenUris.add(ns.getUri())) {
                    continue;
                }
                String prevPrefix = namespaces.put(ns.getUri(), prefix);
                if (prevPrefix != null && !prefix.equals(prevPrefix)) {
                    System.out.printf("namespace prefix change for %s: %s -> %s\n", ns.getUri(), prevPrefix, prefix);
                }
            }
        }

        for (Resources.XmlAttribute rXmlAttr : rElement.getAttributeList()) {
            String value;
            int resourceId = rXmlAttr.getResourceId();
            boolean hasCompiledItem = rXmlAttr.hasCompiledItem();
            if (resourceId != 0 && hasCompiledItem) {
                var resId = ResourceId.create(resourceId);
                Resources.Attribute attr = RAttribute.getById(apk, resId);
                if (attr != null) {
                    value = RAttribute.convertAttrItem(apk, attr, rXmlAttr.getCompiledItem());
                } else {
                    value = RItem.asString(apk, rXmlAttr.getCompiledItem());
                }
            } else if (hasCompiledItem) {
                value = RItem.asString(apk, rXmlAttr.getCompiledItem());
            } else {
                value = rXmlAttr.getValue();
            }

            String ns = rXmlAttr.getNamespaceUri();
            if (ns.isEmpty()) {
                element.setAttribute(rXmlAttr.getName(), value);
            } else {
                String qname = getQualifiedName(ns, namespaces, rXmlAttr.getName());
                element.setAttributeNS(ns, qname, value);
            }
        }

        for (Resources.XmlNode rChildNode : rElement.getChildList()) {
            switch (rChildNode.getNodeCase()) {
                case Resources.XmlNode.NodeCase.ELEMENT ->
                    convertElement(namespaces, rChildNode.getElement(), element, apk);
                case Resources.XmlNode.NodeCase.TEXT ->
                    element.appendChild(doc.createTextNode(rChildNode.getText()));
                default -> throw new IllegalArgumentException(element.toString());
            }
        }
        parent.appendChild(element);
    }
}
