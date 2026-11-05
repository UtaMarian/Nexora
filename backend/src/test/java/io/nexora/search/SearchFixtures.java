package io.nexora.search;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Small real documents built in memory, so tests carry no binary files. */
final class SearchFixtures {
    private SearchFixtures() {}

    static byte[] docx(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>");
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static byte[] textPdf(String text) throws IOException {
        String stream = "BT /F1 18 Tf 50 700 Td (" + text + ") Tj ET";
        return pdf("<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>",
                stream, "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>".getBytes(StandardCharsets.US_ASCII));
    }

    /** A page that is only a picture of text, as a scanner makes. */
    static byte[] scannedPdf(String text) throws IOException {
        byte[] jpeg = image("jpg", text);
        ByteArrayOutputStream object = new ByteArrayOutputStream();
        object.write(("<</Type/XObject/Subtype/Image/Width 1400/Height 300/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length "
                + jpeg.length + ">>\nstream\n").getBytes(StandardCharsets.US_ASCII));
        object.write(jpeg);
        object.write("\nendstream".getBytes(StandardCharsets.US_ASCII));
        return pdf("<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</XObject<</I 5 0 R>>>>>>",
                "q 560 0 0 120 20 500 cm /I Do Q", object.toByteArray());
    }

    private static byte[] pdf(String page, String stream, byte[] resource) throws IOException {
        byte[][] objects = {
                "<</Type/Catalog/Pages 2 0 R>>".getBytes(StandardCharsets.US_ASCII),
                "<</Type/Pages/Kids[3 0 R]/Count 1>>".getBytes(StandardCharsets.US_ASCII),
                page.getBytes(StandardCharsets.US_ASCII),
                ("<</Length " + stream.length() + ">>\nstream\n" + stream + "\nendstream").getBytes(StandardCharsets.US_ASCII),
                resource};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = out.size();
            out.write(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            out.write(objects[i]);
            out.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }
        int xref = out.size();
        StringBuilder tail = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) tail.append(String.format("%010d 00000 n \n", offset));
        tail.append("trailer\n<</Size ").append(objects.length + 1).append("/Root 1 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.write(tail.toString().getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    static byte[] image(String format, String text) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage image = new BufferedImage(1400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1400, 300);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        g.drawString(text, 60, 150);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
