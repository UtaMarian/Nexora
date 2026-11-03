package io.nexora.api;

import io.nexora.api.Dtos.*;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sites")
public class SiteController {
    private final ContentService content;
    private final IdentityService identity;
    private final NodeMapper mapper;

    public SiteController(ContentService content, IdentityService identity, NodeMapper mapper) {
        this.content = content;
        this.identity = identity;
        this.mapper = mapper;
    }

    @GetMapping
    public List<NodeDto> list() {
        CurrentUser user = CurrentUser.get();
        return mapper.many(user, content.visibleSites(user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto create(@Valid @RequestBody CreateSiteRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.createSite(user, body.url(), body.title(), body.description()));
    }

    @PatchMapping("/{id}")
    public NodeDto update(@PathVariable UUID id, @RequestBody UpdateSiteRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.updateSite(user, id, body.title(), body.description()));
    }

    /** The site's libraries (what the site's home page lists). */
    @GetMapping("/{id}/libraries")
    public List<NodeDto> libraries(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        List<Node> libs = content.children(user, id).stream().filter(n -> n.getKind() == Node.Kind.LIBRARY).toList();
        return mapper.many(user, libs);
    }

    @PostMapping("/{id}/libraries")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto createLibrary(@PathVariable UUID id, @Valid @RequestBody CreateLibraryRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.createLibrary(user, id, body.name(), body.title(), body.description()));
    }

    @GetMapping("/{id}/groups")
    public List<PrincipalRef> groups(@PathVariable UUID id) {
        return identity.siteGroups(CurrentUser.get(), id).stream().map(PrincipalRef::of).toList();
    }
}
