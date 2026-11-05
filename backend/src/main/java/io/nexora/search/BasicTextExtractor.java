package io.nexora.search;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads plain text and the zipped XML of modern Office and OpenDocument files without any other service. For
 * development; PDFs, older Office files and OCR need Tika.
 */
public class BasicTextExtractor implements TextExtractor {
    private static final Set<String> ZIPPED = Set.of("docx", "xlsx", "pptx", "odt", "ods", "odp");
    private static final Set<String> PLAIN = Set.of("txt", "md", "csv", "json", "xml", "log", "html", "htm");
    private static final Pattern TEXT_PARTS = Pattern.compile(
            "word/(document|header\\d*|footer\\d*)\\.xml|xl/sharedStrings\\.xml|ppt/slides/slide\\d+\\.xml|content\\.xml");
    private static final int MAX_PART_BYTES = 64 * 1024 * 1024; // bounds zip bombs

    @Override
    public String name() {
        return "Built-in reader (no OCR, PDF or older Office files)";
    }

    @Override
    public boolean supports(String mimeType, String extension) {
        return ZIPPED.contains(extension) || PLAIN.contains(extension);
    }

    @Override
    public String extract(InputStream data, String mimeType, String extension, int maxChars)
            throws IOException, RejectedException {
        if (!ZIPPED.contains(extension)) {
            return new String(data.readNBytes(maxChars * 4), StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(data)) {
            ZipEntry entry;
            while (out.length() < maxChars && (entry = zip.getNextEntry()) != null) {
                if (TEXT_PARTS.matcher(entry.getName()).matches()) readXml(zip.readNBytes(MAX_PART_BYTES), out, maxChars);
            }
        } catch (XMLStreamException e) {
            throw new RejectedException("The file is damaged");
        }
        return out.length() > maxChars ? out.substring(0, maxChars) : out.toString();
    }

    private static void readXml(byte[] xml, StringBuilder out, int maxChars) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        while (reader.hasNext() && out.length() < maxChars) {
            if (reader.next() == XMLStreamConstants.CHARACTERS) out.append(reader.getText()).append(' ');
        }
        reader.close();
    }

    @Override
    public String status() {
        return "Text and modern Office files only";
    }
}
