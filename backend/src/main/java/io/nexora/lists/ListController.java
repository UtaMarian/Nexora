package io.nexora.lists;

import io.nexora.api.Dtos.PrincipalRef;
import io.nexora.api.NodeMapper;
import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** Saved views, item history and attachment covers. Attachments themselves are ordinary files under the item. */
@RestController
@RequestMapping("/api/v1/nodes/{id}")
public class ListController {
    private final ListViewService views;
    private final ContentService content;
    private final NodeMapper mapper;

    public ListController(ListViewService views, ContentService content, NodeMapper mapper) {
        this.views = views;
        this.content = content;
        this.mapper = mapper;
    }

    public record ItemVersionDto(int version, boolean current, String title, Map<String, Object> values, PrincipalRef createdBy, Instant createdAt) {}

    @GetMapping("/views")
    public List<ListViewService.View> views(@PathVariable UUID id) {
        return views.list(CurrentUser.get(), id);
    }

    @PostMapping("/views")
    @ResponseStatus(HttpStatus.CREATED)
    public ListViewService.View create(@PathVariable UUID id, @RequestBody ListViewService.View body) {
        return views.create(CurrentUser.get(), id, body);
    }

    @PutMapping("/views/{viewId}")
    public ListViewService.View update(@PathVariable UUID id, @PathVariable String viewId, @RequestBody ListViewService.View body) {
        return views.update(CurrentUser.get(), id, viewId, body);
    }

    @DeleteMapping("/views/{viewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @PathVariable String viewId) {
        views.delete(CurrentUser.get(), id, viewId);
    }

    /** The first image attached to each item, for gallery views: item id to file id. */
    @GetMapping("/covers")
    public Map<UUID, UUID> covers(@PathVariable UUID id) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        for (Node image : content.attachedImages(CurrentUser.get(), id)) out.putIfAbsent(image.getParentId(), image.getId());
        return out;
    }

    @GetMapping("/item-versions")
    public List<ItemVersionDto> itemVersions(@PathVariable UUID id) {
        List<ItemVersion> history = content.itemHistory(CurrentUser.get(), id);
        int latest = history.isEmpty() ? 0 : history.get(0).getVersion();
        return history.stream().map(v -> new ItemVersionDto(v.getVersion(), v.getVersion() == latest, v.getTitle(), v.getMetadata(),
                mapper.ref(v.getCreatedBy()), v.getCreatedAt())).toList();
    }

    @PostMapping("/item-versions/{version}/restore")
    public io.nexora.api.Dtos.NodeDto restore(@PathVariable UUID id, @PathVariable int version) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.restoreItemVersion(user, id, version));
    }
}
