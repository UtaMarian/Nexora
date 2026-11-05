package io.nexora.content;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Knows which files keep their drafts from people who only read, under the library's "Who can see drafts" setting.
 * It holds no opinion on people: {@code PermissionService} decides who that applies to.
 */
@Component
public class DraftPolicy {
    private final NodeRepository nodes;
    private final FileVersionRepository versions;

    public DraftPolicy(NodeRepository nodes, FileVersionRepository versions) {
        this.nodes = nodes;
        this.versions = versions;
    }

    public boolean restricted(Node file) {
        return !restricted(List.of(file)).isEmpty();
    }

    /** The ids of the files among {@code candidates} whose library hides drafts. */
    public Set<UUID> restricted(Collection<Node> candidates) {
        Map<UUID, Boolean> folders = new HashMap<>(); // files in one folder share an answer
        Set<UUID> out = new HashSet<>();
        for (Node n : candidates) {
            if (n.getKind() == Node.Kind.FILE && hidesDrafts(n.getParentId(), folders)) out.add(n.getId());
        }
        return out;
    }

    private boolean hidesDrafts(UUID id, Map<UUID, Boolean> folders) {
        if (id == null) return false;
        Boolean known = folders.get(id);
        if (known != null) return known;
        Node n = nodes.findById(id).orElse(null);
        boolean hides;
        if (n == null || n.getKind() == Node.Kind.SITE || n.getKind() == Node.Kind.LIST) hides = false;
        else if (n.getKind() == Node.Kind.LIBRARY) hides = LibrarySettings.of(n).hidesDrafts();
        else hides = hidesDrafts(n.getParentId(), folders);
        folders.put(id, hides);
        return hides;
    }

    /** The latest major version, if the file has ever been published. */
    public Optional<FileVersion> published(Node file) {
        return Optional.ofNullable(published(List.of(file.getId())).get(file.getId()));
    }

    public Map<UUID, FileVersion> published(Collection<UUID> fileIds) {
        if (fileIds.isEmpty()) return Map.of();
        return versions.findLatestMajors(fileIds).stream().collect(Collectors.toMap(FileVersion::getNodeId, Function.identity()));
    }
}
