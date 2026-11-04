package io.nexora.pages;

import io.nexora.acl.Right;
import io.nexora.api.Dtos.NodeDto;
import io.nexora.api.Dtos.PrincipalRef;
import io.nexora.api.NodeMapper;
import io.nexora.common.ApiException;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Modern pages, news posts and the page editor's image uploads. */
@RestController
@RequestMapping("/api/v1")
public class PageController {
    private final PageService pages;
    private final IdentityService identity;
    private final NodeMapper mapper;
    private final io.nexora.portal.HubService hubs;
    private final EmbedPolicy embeds;
    private final io.nexora.audit.AuditService audit;

    public PageController(PageService pages, IdentityService identity, NodeMapper mapper, io.nexora.portal.HubService hubs,
                          EmbedPolicy embeds, io.nexora.audit.AuditService audit) {
        this.hubs = hubs;
        this.embeds = embeds;
        this.audit = audit;
        this.pages = pages;
        this.identity = identity;
        this.mapper = mapper;
    }

    public record PageSummary(UUID id, UUID siteId, String name, SitePage.Kind kind, String title, String description,
                              String url, UUID bannerImageId, String excerpt, boolean published, boolean hasDraft,
                              int version, PrincipalRef author, Instant publishedAt, PrincipalRef modifiedBy,
                              Instant modifiedAt, boolean home, String siteTitle) {}

    public record PageDetail(PageSummary page, PageService.Content published, PageService.Content draft, int revision,
                             boolean canEdit, boolean canDelete, boolean canSetHome) {}

    public record VersionDto(int version, String title, PrincipalRef publishedBy, Instant publishedAt) {}

    public record CreatePage(String title, SitePage.Kind kind, String template) {}

    public record SaveTemplate(String title) {}

    public record HomePage(UUID pageId) {}

    @GetMapping("/sites/{siteId}/pages")
    public List<PageSummary> list(@PathVariable UUID siteId, @RequestParam(required = false) SitePage.Kind kind) {
        CurrentUser user = CurrentUser.get();
        Node site = pages.site(user, siteId);
        return summaries(site, pages.list(user, site, kind));
    }

    @GetMapping("/sites/{siteId}/news")
    public List<PageSummary> news(@PathVariable UUID siteId, @RequestParam(defaultValue = "10") int limit,
                                  @RequestParam(defaultValue = "site") String scope) {
        CurrentUser user = CurrentUser.get();
        Node site = pages.site(user, siteId);
        if (!"hub".equals(scope)) return summaries(site, pages.news(user, site, limit));
        // the latest across every site in the hub that this person can see
        List<PageSummary> all = new ArrayList<>();
        for (Node member : hubs.sitesInHub(user, site)) all.addAll(summaries(member, pages.news(user, member, limit)));
        all.sort(Comparator.comparing(PageSummary::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return all.subList(0, Math.min(limit, all.size()));
    }

    @PostMapping("/sites/{siteId}/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageDetail create(@PathVariable UUID siteId, @RequestBody CreatePage body) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.create(user, siteId, body.title(), body.kind(), body.template()));
    }

    @GetMapping("/sites/{siteId}/page-templates")
    public List<PageService.TemplateInfo> templates(@PathVariable UUID siteId) {
        CurrentUser user = CurrentUser.get();
        return pages.templates(user, pages.site(user, siteId));
    }

    @PostMapping("/pages/{id}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    public PageDetail saveAsTemplate(@PathVariable UUID id, @RequestBody SaveTemplate body) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.saveAsTemplate(user, id, body.title()));
    }

    /** The outside sites pages may embed. */
    @GetMapping("/embed-hosts")
    public List<String> embedHosts() {
        return embeds.hosts();
    }

    @PutMapping("/admin/embed-hosts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEmbedHosts(@RequestBody List<String> hosts) {
        CurrentUser user = CurrentUser.get();
        try {
            embeds.setHosts(hosts, user.id());
        } catch (IllegalArgumentException e) {
            throw io.nexora.common.ApiException.badRequest("invalid_host", e.getMessage());
        }
        audit.record(user, "EMBED_HOSTS_UPDATE", null, Map.of("hosts", String.join(", ", hosts)));
    }

    @PutMapping("/sites/{siteId}/home-page")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setHome(@PathVariable UUID siteId, @RequestBody HomePage body) {
        pages.setHomePage(CurrentUser.get(), siteId, body.pageId());
    }

    @PostMapping(path = "/sites/{siteId}/page-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto uploadImage(@PathVariable UUID siteId, @RequestParam("file") MultipartFile file) {
        CurrentUser user = CurrentUser.get();
        if (file.getSize() > PageService.MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "image_too_large", "Images are limited to 10 MB");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("nexora-image", ".upload");
            file.transferTo(tmp);
            return mapper.one(user, pages.uploadImage(user, siteId, file.getOriginalFilename(), tmp));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp directory cleanup will take it
                }
            }
        }
    }

    @GetMapping("/pages/resolve")
    public PageDetail resolve(@RequestParam String path) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.resolve(user, path));
    }

    @GetMapping("/pages/{id}")
    public PageDetail get(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.get(user, id));
    }

    @PutMapping("/pages/{id}/draft")
    public PageDetail saveDraft(@PathVariable UUID id, @RequestBody PageService.Draft body) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.saveDraft(user, id, body));
    }

    @PostMapping("/pages/{id}/publish")
    public PageDetail publish(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.publish(user, id));
    }

    @PostMapping("/pages/{id}/discard")
    public PageDetail discard(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.discardDraft(user, id));
    }

    @DeleteMapping("/pages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        pages.delete(CurrentUser.get(), id);
    }

    @GetMapping("/pages/{id}/versions")
    public List<VersionDto> versions(@PathVariable UUID id) {
        List<SitePageVersion> list = pages.versions(CurrentUser.get(), id);
        Map<UUID, Principal> people = identity.byIds(list.stream().map(SitePageVersion::getPublishedBy).toList());
        return list.stream()
                .map(v -> new VersionDto(v.getVersion(), v.getTitle(), PrincipalRef.of(people.get(v.getPublishedBy())), v.getPublishedAt()))
                .toList();
    }

    @PostMapping("/pages/{id}/versions/{version}/restore")
    public PageDetail restore(@PathVariable UUID id, @PathVariable int version) {
        CurrentUser user = CurrentUser.get();
        return detail(user, pages.restore(user, id, version));
    }

    // ---- mapping ----

    private PageDetail detail(CurrentUser user, SitePage p) {
        Node site = pages.site(user, p.getSiteId());
        boolean canEdit = pages.can(user, site, Right.EDIT_ITEMS);
        return new PageDetail(summaries(site, List.of(p)).get(0), pages.published(p), canEdit ? pages.draft(p) : null,
                p.getRevision(), canEdit, pages.can(user, site, Right.DELETE_ITEMS), pages.can(user, site, Right.MANAGE_WEB));
    }

    private List<PageSummary> summaries(Node site, List<SitePage> list) {
        Set<UUID> ids = new HashSet<>();
        for (SitePage p : list) {
            ids.add(p.getCreatedBy());
            ids.add(p.getModifiedBy());
            if (p.getPublishedBy() != null) ids.add(p.getPublishedBy());
        }
        Map<UUID, Principal> people = identity.byIds(ids);
        UUID home = pages.homePageId(site).orElse(null);
        List<PageSummary> out = new ArrayList<>();
        for (SitePage p : list) {
            PageService.Content c = pages.current(p);
            UUID author = p.getPublishedBy() != null ? p.getPublishedBy() : p.getCreatedBy();
            out.add(new PageSummary(p.getId(), p.getSiteId(), p.getName(), p.getKind(), c.title(), c.description(),
                    pages.url(site, p), c.bannerImageId(), c.canvas().plainText(240), p.isPublished(), p.hasDraft(),
                    p.getPublishedVersion(), PrincipalRef.of(people.get(author)), p.getPublishedAt(),
                    PrincipalRef.of(people.get(p.getModifiedBy())), p.getModifiedAt(), p.getId().equals(home), site.getTitle()));
        }
        return out;
    }
}
