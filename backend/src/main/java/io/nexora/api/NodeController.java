package io.nexora.api;

import io.nexora.api.Dtos.*;
import io.nexora.content.ContentService;
import io.nexora.content.LibrarySettings;
import io.nexora.content.Node;
import io.nexora.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Generic operations on any node: sites, libraries, folders and files. */
@RestController
@RequestMapping("/api/v1")
public class NodeController {
    private final ContentService content;
    private final NodeMapper mapper;

    public NodeController(ContentService content, NodeMapper mapper) {
        this.content = content;
        this.mapper = mapper;
    }

    @GetMapping("/nodes/{id}")
    public NodeDetail get(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return detail(user, content.get(user, id));
    }

    /** Resolves a SharePoint-style URL, e.g. {@code ?path=/sites/hr/Shared Documents/Leave.docx}. */
    @GetMapping("/resolve")
    public NodeDetail resolve(@RequestParam String path) {
        CurrentUser user = CurrentUser.get();
        return detail(user, content.resolve(user, path));
    }

    @GetMapping("/nodes/{id}/children")
    public List<NodeDto> children(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        return mapper.many(user, content.children(user, id));
    }

    @PostMapping("/nodes/{id}/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeDto createFolder(@PathVariable UUID id, @Valid @RequestBody CreateFolderRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.createFolder(user, id, body.name()));
    }

    @PatchMapping("/nodes/{id}")
    public NodeDto update(@PathVariable UUID id, @RequestBody UpdateNodeRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.update(user, id, body.name(), body.title(), body.description(), body.metadata()));
    }

    @PostMapping("/nodes/{id}/move")
    public NodeDto move(@PathVariable UUID id, @Valid @RequestBody MoveRequest body) {
        CurrentUser user = CurrentUser.get();
        return mapper.one(user, content.move(user, id, body.targetParentId()));
    }

    /** Moves the item (and everything under it) to the recycle bin. */
    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        content.delete(CurrentUser.get(), id);
    }

    // ---- library settings & columns ----

    @GetMapping("/nodes/{id}/settings")
    public LibrarySettingsDto settings(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.get();
        LibrarySettings s = LibrarySettings.of(content.libraryOf(content.get(user, id)));
        return dto(s);
    }

    @PutMapping("/nodes/{id}/settings")
    public LibrarySettingsDto updateSettings(@PathVariable UUID id, @RequestBody LibrarySettingsDto body) {
        CurrentUser user = CurrentUser.get();
        // a caller that does not know the field must not reopen a library's drafts
        LibrarySettings.DraftVisibility drafts = body.draftVisibility() != null ? body.draftVisibility()
                : LibrarySettings.of(content.libraryOf(content.get(user, id))).draftVisibility();
        return dto(content.updateLibrarySettings(user, id, new LibrarySettings(
                body.versioningEnabled(), body.minorVersionsEnabled(), body.requireCheckout(), body.maxVersions(), drafts)));
    }

    private static LibrarySettingsDto dto(LibrarySettings s) {
        return new LibrarySettingsDto(s.versioningEnabled(), s.minorVersionsEnabled(), s.requireCheckout(), s.maxVersions(), s.draftVisibility());
    }

    @GetMapping("/nodes/{id}/columns")
    public List<ColumnDto> columns(@PathVariable UUID id) {
        return content.columns(CurrentUser.get(), id).stream().map(ColumnDto::of).toList();
    }

    @PostMapping("/nodes/{id}/columns")
    @ResponseStatus(HttpStatus.CREATED)
    public ColumnDto addColumn(@PathVariable UUID id, @Valid @RequestBody CreateColumnRequest body) {
        return ColumnDto.of(content.addColumn(CurrentUser.get(), id, body.displayName(), body.type(), body.required(),
                body.choices(), body.lookupListId()));
    }

    @DeleteMapping("/nodes/{id}/columns/{columnId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteColumn(@PathVariable UUID id, @PathVariable UUID columnId) {
        content.deleteColumn(CurrentUser.get(), id, columnId);
    }
    private NodeDetail detail(CurrentUser user, Node node) {
        List<Crumb> crumbs = content.ancestry(node).stream()
                .map(n -> new Crumb(n.getId(), n.getKind(), n.getName(), n.getTitle(), n.getPath()))
                .toList();
        return new NodeDetail(mapper.one(user, node), crumbs);
    }
}
