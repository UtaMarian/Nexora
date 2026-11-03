package io.nexora.portal;

import io.nexora.api.Dtos.NodeDto;
import io.nexora.api.NodeMapper;
import io.nexora.common.ApiException;
import io.nexora.content.ContentService;
import io.nexora.content.ListTemplate;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Site templates, branding ("Change the look"), lists and list items. */
@RestController
@RequestMapping("/api/v1")
public class PortalController {
    private final PortalService portal;
    private final ContentService content;
    private final NodeMapper mapper;
    private final io.nexora.pages.PageService pages;

    public PortalController(PortalService portal, ContentService content, NodeMapper mapper,
                            io.nexora.pages.PageService pages) {
        this.pages = pages;
        this.portal = portal;
        this.content = content;
        this.mapper = mapper;
    }

    public record TemplateDto(String id, String title, String description) {}

    public record Templates(List<TemplateDto> sites, List<TemplateDto> lists) {}

    public record DesignDto(SiteDesign design, String logoUrl, String template, UUID homePageId) {}

    public record CreateListRequest(@NotBlank String name, String title, String description, ListTemplate template) {}

    public record CreateItemRequest(String title, Map<String, Object> values) {}

    @GetMapping("/templates")
    public Templates templates() {
        return new Templates(
                Arrays.stream(SiteTemplate.values()).map(t -> new TemplateDto(t.name(), t.title(), t.description())).toList(),
                Arrays.stream(ListTemplate.values()).map(t -> new TemplateDto(t.name(), t.title(), t.description())).toList());
    }

    // ---- design ----

    @GetMapping("/sites/{id}/design")
    public DesignDto design(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return dto(user, id, portal.design(user, id));
    }

    @PutMapping("/sites/{id}/design")
    public DesignDto updateDesign(@PathVariable UUID id, @RequestBody SiteDesign body) {
        CurrentUser user = CurrentUser.get();
        return dto(user, id, portal.updateDesign(user, id, body));
    }

    @GetMapping("/sites/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable UUID id) {
        return portal.logo(CurrentUser.get(), id)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.mimeType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                        .eTag("\"" + l.sha256() + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "sandbox")
                        .body(l.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(path = "/sites/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DesignDto uploadLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        CurrentUser user = CurrentUser.get();
        if (file.getSize() > PortalService.MAX_LOGO_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "logo_too_large", "Logos are limited to 512 KB");
        }
        portal.setLogo(user, id, file.getBytes());
        return dto(user, id, portal.design(user, id));
    }

    @DeleteMapping("/sites/{id}/logo")
    public DesignDto deleteLogo(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        portal.deleteLogo(user, id);
        return dto(user, id, portal.design(user, id));
    }

    // ---- lists ----

    @GetMapping("/sites/{id}/lists")
    public List<NodeDto> lists(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return mapper.many(user, content.children(user, id).stream().filter(n -> n.getKind() == Node.Kind.LIST).toList());
    }

    @PostMapping("/sites/{id}/lists")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto createList(@PathVariable UUID id, @Valid @RequestBody CreateListRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.createList(user, id, body.name(), body.title(), body.description(), body.template()));
    }

    @PostMapping("/nodes/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto createItem(@PathVariable UUID id, @RequestBody CreateItemRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.createItem(user, id, body.title(), body.values()));
    }

    private DesignDto dto(CurrentUser user, UUID siteId, SiteDesign design) {
        Node site = content.get(user, siteId);
        String logo = portal.logoVersion(site).map(v -> "/api/v1/sites/" + siteId + "/logo?v=" + v).orElse(null);
        return new DesignDto(design, logo, portal.template(site).orElse(null), pages.homePageId(site).orElse(null));
    }
}
