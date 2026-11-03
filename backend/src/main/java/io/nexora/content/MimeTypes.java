package io.nexora.content;

import java.net.URLConnection;
import java.util.Locale;
import java.util.Map;

/** Content-type guessing with the Office formats the JDK does not know about. */
public final class MimeTypes {
    private MimeTypes() {}

    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("doc", "application/msword"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("vsdx", "application/vnd.ms-visio.drawing"),
            Map.entry("msg", "application/vnd.ms-outlook"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("zip", "application/zip"));

    public static String resolve(String fileName, String declared) {
        String ext = extension(fileName);
        if (KNOWN.containsKey(ext)) return KNOWN.get(ext);
        if (declared != null && !declared.isBlank() && !declared.equals("application/octet-stream")) return declared;
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : "application/octet-stream";
    }

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
