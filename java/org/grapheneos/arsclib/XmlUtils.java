package org.grapheneos.arsclib;

import org.w3c.dom.Document;

import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

class XmlUtils {
    static Document createDocument() {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }

        Document res = builder.newDocument();
        return res;
    }

    static String format(Document doc) {
        TransformerFactory transformerFactory = TransformerFactory.newDefaultInstance();
        Transformer transformer;
        try {
            transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            doc.normalizeDocument();
            DOMSource source = new DOMSource(doc);
            var w = new StringWriter();
            w.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            transformer.transform(source, new StreamResult(w));
            return w.toString();
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }
    }
}
