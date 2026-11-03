package io.nexora.api;

import io.nexora.api.Dtos.*;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import io.nexora.identity.IdentityService;
import io.nexora.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Items users deleted. People see what they deleted themselves; site owners see everything deleted in
 * their site; administrators see everything.
 */
@RestController
@RequestMapping("/api/v1/recycle-bin")
public class RecycleBinController {
    private final ContentService content;
    private final IdentityService identity;
    private final NodeMapper mapper;

    public RecycleBinController(ContentService content, IdentityService identity, NodeMapper mapper) {
        this.content = content;
        this.identity = identity;
        this.mapper = mapper;
    }

    @GetMapping
    public List<RecycleBinItem> list(@RequestParam(required = false) UUID siteId) {
        List<Node> items = content.recycleBin(CurrentUser.get(), siteId);
        Map<UUID, Principal> people = identity.byIds(items.stream().map(Node::getDeletedBy).filter(Objects::nonNull).toList());
        return items.stream()
                .map(n -> new RecycleBinItem(n.getId(), n.getKind(), n.getName(), n.getPath(), n.getSiteId(),
                        PrincipalRef.of(people.get(n.getDeletedBy())), n.getDeletedAt()))
                .toList();
    }

    @PostMapping("/{id}/restore")
    public NodeDto restore(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.restore(user, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id) {
        content.purge(CurrentUser.get(), id);
    }
}
