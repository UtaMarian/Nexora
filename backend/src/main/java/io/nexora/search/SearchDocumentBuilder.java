package io.nexora.search;

import io.nexora.acl.PermissionService;
import io.nexora.content.FileVersion;
import io.nexora.content.FileVersionRepository;
import io.nexora.content.Node;
import io.nexora.identity.Principal;
import io.nexora.identity.PrincipalRepository;
import io.nexora.pages.PageService;
import io.nexora.pages.SitePage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Turns nodes and pages into search documents. Call inside a transaction. */
@Component
public class SearchDocumentBuilder {
    private static final Set<String> WORD = Set.of("doc", "docx", "docm", "dot", "dotx", "odt", "rtf");
    private static final Set<String> EXCEL = Set.of("xls", "xlsx", "xlsm", "xlsb", "ods", "csv");
    private static final Set<String> POWERPOINT = Set.of("ppt", "pptx", "pptm", "pps", "ppsx", "odp");
    private static final Set<String> ARCHIVES = Set.of("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "iso");

    private final SearchProperties properties;
    private final TextExtractor extractor;
    private final PermissionService permissions;
    private final FileVersionRepository versions;
    private final PrincipalRepository principals;
    private final PageService pages;

    public SearchDocumentBuilder(SearchProperties properties, TextExtractor extractor, PermissionService permissions,
                                 FileVersionRepository versions, PrincipalRepository principals, PageService pages) {
        this.properties = properties;
        this.extractor = extractor;
        this.permissions = permissions;
        this.versions = versions;
        this.principals = principals;
        this.pages = pages;
    }

    /** The blob of the version everyone reads. A checked-out draft stays private to its author and is never indexed. */
    public Optional<FileVersion> currentVersion(Node file) {
        return file.getCurrentVersionId() == null ? Optional.empty() : versions.findById(file.getCurrentVersionId());
    }

    public boolean wantsText(Node node) {
        if (node.getKind() != Node.Kind.FILE || node.getSizeBytes() == null || node.getSizeBytes() == 0) return false;
        if (node.getSizeBytes() > properties.maxFileSize().toBytes()) return false;
        String mime = node.getMimeType() == null ? "" : node.getMimeType();
        String extension = extension(node.getName());
        if (mime.startsWith("video/") || mime.startsWith("audio/") || ARCHIVES.contains(extension)) return false;
        return extractor.supports(mime, extension);
    }

    public SearchDocument node(Node n, String content) {
        String extension = n.getKind() == Node.Kind.FILE ? extension(n.getName()) : "";
        boolean item = n.getKind() == Node.Kind.LIST_ITEM; // an item's name is only its number
        return new SearchDocument(n.getId(), type(n, extension), n.getTitle() != null ? n.getTitle() : n.getName(),
                item ? "" : n.getName(), item ? "" : words(n.getName()), n.getDescription() == null ? "" : n.getDescription(),
                columnText(n.getMetadata()), content == null ? "" : content, n.getPath(), siteSlug(n.getPath()), n.getSiteId(),
                extension, author(n.getModifiedBy()), n.getModifiedAt(), n.getSizeBytes() == null ? 0 : n.getSizeBytes(),
                permissions.aclSource(n).getId());
    }

    /** The published page only: drafts stay private to editors. */
    public SearchDocument page(SitePage p, Node site) {
        PageService.Content c = pages.published(p);
        Instant at = p.getPublishedAt() != null ? p.getPublishedAt() : p.getModifiedAt();
        UUID by = p.getPublishedBy() != null ? p.getPublishedBy() : p.getModifiedBy();
        return new SearchDocument(p.getId(), p.getKind() == SitePage.Kind.NEWS ? "news" : "page", c.title(), p.getName(),
                words(p.getName()), c.description() == null ? "" : c.description(), "",
                c.canvas().plainText(properties.maxTextChars()), pages.url(site, p), siteSlug(site.getPath()), site.getId(),
                "", author(by), at, 0, site.getId());
    }

    private String author(UUID id) {
        return id == null ? "" : principals.findById(id).map(Principal::getDisplayName).orElse("");
    }

    private static String siteSlug(String path) {
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String type(Node n, String extension) {
        return switch (n.getKind()) {
            case SITE -> "site";
            case LIBRARY -> "library";
            case LIST -> "list";
            case FOLDER -> "folder";
            case LIST_ITEM -> "item";
            case FILE -> fileType(extension, n.getMimeType() == null ? "" : n.getMimeType());
        };
    }

    private static String fileType(String extension, String mime) {
        if (WORD.contains(extension)) return "word";
        if (EXCEL.contains(extension)) return "excel";
        if (POWERPOINT.contains(extension)) return "powerpoint";
        if (extension.equals("pdf")) return "pdf";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.startsWith("text/")) return "text";
        return "file";
    }

    /** "Q3_budgetReport-2024.xlsx" becomes "Q3 budget Report 2024 xlsx", so each part of a name can be found. */
    static String words(String name) {
        return name.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Text of column values. A person column is a snapshot of {id, login, displayName}, and ids are not words. */
    static String columnText(Map<String, Object> metadata) {
        StringBuilder b = new StringBuilder();
        collect(metadata, b);
        return b.toString().strip();
    }

    private static void collect(Object value, StringBuilder b) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (!"id".equals(k)) collect(v, b);
            });
        } else if (value instanceof Collection<?> c) {
            c.forEach(v -> collect(v, b));
        } else if (value != null) {
            b.append(value).append(' ');
        }
    }
}
