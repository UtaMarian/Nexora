package io.nexora.dav;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Small helpers for WebDAV XML (RFC 4918). Output is built as text; input is parsed safely (no DTDs). */
final class DavXml {
    private DavXml() {}

    static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    static final String MULTISTATUS_OPEN = HEADER
            + "<D:multistatus xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\">\n";
    static final String MULTISTATUS_CLOSE = "</D:multistatus>\n";

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') b.append(c);
                }
            }
        }
        return b.toString();
    }

    static String rfc1123(Instant t) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atOffset(ZoneOffset.UTC));
    }

    static String iso(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    static Document parse(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code <D:owner>} of a LOCK request, serialized back as text for lockdiscovery. */
    static String lockOwner(Document doc) {
        if (doc == null) return null;
        NodeList owners = doc.getElementsByTagNameNS("DAV:", "owner");
        if (owners.getLength() == 0) return null;
        Node owner = owners.item(0);
        NodeList hrefs = ((Element) owner).getElementsByTagNameNS("DAV:", "href");
        String text = hrefs.getLength() > 0 ? hrefs.item(0).getTextContent() : owner.getTextContent();
        return text == null ? null : text.strip();
    }

    /** Property names (namespace, local name) mentioned in a PROPPATCH body. */
    static List<String[]> proppatchNames(Document doc) {
        List<String[]> out = new ArrayList<>();
        if (doc == null) return out;
        NodeList props = doc.getElementsByTagNameNS("DAV:", "prop");
        for (int i = 0; i < props.getLength(); i++) {
            NodeList children = props.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node c = children.item(j);
                if (c.getNodeType() == Node.ELEMENT_NODE) {
                    out.add(new String[]{c.getNamespaceURI() == null ? "" : c.getNamespaceURI(), c.getLocalName()});
                }
            }
        }
        return out;
    }

    static String activeLock(String token, String owner, long timeoutSeconds, String rootHref) {
        return "<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:depth>0</D:depth>"
                + (owner == null ? "" : "<D:owner><D:href>" + esc(owner) + "</D:href></D:owner>")
                + "<D:timeout>Second-" + timeoutSeconds + "</D:timeout>"
                + "<D:locktoken><D:href>" + esc(token) + "</D:href></D:locktoken>"
                + "<D:lockroot><D:href>" + esc(rootHref) + "</D:href></D:lockroot>"
                + "</D:activelock>";
    }

    static String lockResponse(String token, String owner, long timeoutSeconds, String rootHref) {
        return HEADER + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>"
                + activeLock(token, owner, timeoutSeconds, rootHref)
                + "</D:lockdiscovery></D:prop>\n";
    }
}
