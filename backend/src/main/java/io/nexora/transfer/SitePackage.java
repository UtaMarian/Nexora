package io.nexora.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The portable form of one site: a ZIP with {@code manifest.json} (this record) and {@code blobs/<sha256>} for
 * every file version. Used for site backups, farm backups, export/import between farms and restores.
 *
 * <p>People are referenced by kind and login, never by database id, so a package can be restored into another
 * farm whose directory knows the same accounts. The site's own Owners / Members / Visitors groups are
 * referenced by their role ({@code siteRole}) because their logins change with the site URL.
 */
public record SitePackage(String format, int formatVersion, Instant exportedAt, String exportedBy, SiteRec site,
                          List<GroupRec> groups, List<NodeRec> nodes, List<ColumnRec> columns, List<AclRec> acl,
                          List<PageRec> pages, LogoRec logo) {

    public static final String FORMAT = "nexora-site";
    public static final int VERSION = 1;
    public static final String MANIFEST = "manifest.json";

    public static String blobEntry(String sha256) {
        return "blobs/" + sha256;
    }

    /** A principal reference. {@code siteRole} is owners / members / visitors for the site's own groups. */
    public record PrincipalRef(String kind, String login, String displayName, String email, String siteRole) {}

    public record SiteRec(UUID id, String name, String title, String description, Map<String, Object> settings,
                          PrincipalRef createdBy, Instant createdAt) {}

    public record GroupRec(PrincipalRef group, List<PrincipalRef> members) {}

    public record VersionRec(int major, int minor, String sha256, long sizeBytes, String mimeType, String fileName,
                             String comment, PrincipalRef createdBy, Instant createdAt) {}

    public record NodeRec(UUID id, UUID parentId, String kind, String name, String title, String description,
                          Map<String, Object> metadata, Map<String, Object> settings, boolean inheritsPermissions,
                          PrincipalRef createdBy, Instant createdAt, PrincipalRef modifiedBy, Instant modifiedAt,
                          List<VersionRec> versions) {}

    public record ColumnRec(UUID containerId, String internalName, String displayName, String type, boolean required,
                            List<String> choices, int position) {}

    public record AclRec(UUID nodeId, PrincipalRef principal, String level) {}

    public record PageVersionRec(int version, String title, String description, UUID bannerImageId,
                                 Map<String, Object> canvas, PrincipalRef publishedBy, Instant publishedAt) {}

    public record PageRec(String name, String kind, String title, String description, UUID bannerImageId,
                          Map<String, Object> canvas, String draftTitle, String draftDescription, UUID draftBannerId,
                          Map<String, Object> draftCanvas, int publishedVersion, PrincipalRef createdBy,
                          Instant createdAt, PrincipalRef modifiedBy, Instant modifiedAt, PrincipalRef publishedBy,
                          Instant publishedAt, boolean home, List<PageVersionRec> versions) {}

    public record LogoRec(String mimeType, String sha256) {}
}
