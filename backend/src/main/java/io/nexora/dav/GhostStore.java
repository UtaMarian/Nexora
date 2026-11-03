package io.nexora.dav;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user scratch space for the temporary and lock files desktop apps create next to documents
 * ({@code ~$Report.docx}, {@code ~WRL0001.tmp}, {@code desktop.ini}, LibreOffice {@code .~lock.*#} ...).
 *
 * <p>These never become documents. This also lets the "safe save" pattern of Office on a mapped drive keep
 * version history: Word renames {@code Report.docx} to a temp name, renames its new temp file to
 * {@code Report.docx} and deletes the old one. Here the first rename only records an <em>alias</em> (the real
 * document stays put), and the second rename becomes a new version of the same document.
 */
@Component
public class GhostStore {
    private static final Duration TTL = Duration.ofHours(2);

    /** Either temp content ({@code data}) or an alias to a real document ({@code aliasOf}). */
    public record Ghost(String path, Path data, UUID aliasOf, long size, Instant modified) {
        public boolean isAlias() { return aliasOf != null; }
    }

    private final Map<String, Ghost> ghosts = new ConcurrentHashMap<>();

    public static boolean isGhostName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("~$") || n.startsWith("~wr") || n.startsWith("._") || n.startsWith(".~lock.")
                || n.endsWith(".tmp") || n.equals("desktop.ini") || n.equals("thumbs.db") || n.equals(".ds_store");
    }

    private static String key(UUID user, String path) {
        return user + "|" + path.toLowerCase(Locale.ROOT);
    }

    public Optional<Ghost> get(UUID user, String path) {
        Ghost g = ghosts.get(key(user, path));
        if (g != null && g.modified().plus(TTL).isBefore(Instant.now())) {
            remove(user, path);
            return Optional.empty();
        }
        return Optional.ofNullable(g);
    }

    public void putData(UUID user, String path, Path tempFile) throws IOException {
        Path stored = Files.createTempFile("nexora-ghost-", ".bin");
        Files.copy(tempFile, stored, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        replace(user, path, new Ghost(path, stored, null, Files.size(stored), Instant.now()));
    }

    public void putAlias(UUID user, String path, UUID nodeId) {
        replace(user, path, new Ghost(path, null, nodeId, 0, Instant.now()));
    }

    /** Moves a ghost to a new (ghost) path, keeping its content or alias. */
    public void rename(UUID user, String from, String to) {
        Ghost g = ghosts.remove(key(user, from));
        if (g != null) replace(user, to, new Ghost(to, g.data(), g.aliasOf(), g.size(), Instant.now()));
    }

    public void remove(UUID user, String path) {
        Ghost g = ghosts.remove(key(user, path));
        if (g != null && g.data() != null) deleteQuietly(g.data());
    }

    /** Is this real document currently "renamed away" to a temp name by this user? */
    public boolean isAliasedAway(UUID user, UUID nodeId) {
        String prefix = user + "|";
        return ghosts.entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith(prefix) && nodeId.equals(e.getValue().aliasOf()));
    }

    public void dropAliasesOf(UUID user, UUID nodeId) {
        String prefix = user + "|";
        ghosts.entrySet().removeIf(e -> e.getKey().startsWith(prefix) && nodeId.equals(e.getValue().aliasOf()));
    }

    /** Ghosts directly inside a folder, for directory listings. */
    public List<Ghost> list(UUID user, String parentPath) {
        String prefix = key(user, parentPath) + "/";
        List<Ghost> out = new ArrayList<>();
        ghosts.forEach((k, g) -> {
            if (k.startsWith(prefix) && k.indexOf('/', prefix.length()) < 0) out.add(g);
        });
        return out;
    }

    private void replace(UUID user, String path, Ghost g) {
        Ghost old = ghosts.put(key(user, path), g);
        if (old != null && old.data() != null && !old.data().equals(g.data())) deleteQuietly(old.data());
    }

    @Scheduled(fixedDelay = 600_000)
    public void expire() {
        Instant cutoff = Instant.now().minus(TTL);
        ghosts.entrySet().removeIf(e -> {
            boolean old = e.getValue().modified().isBefore(cutoff);
            if (old && e.getValue().data() != null) deleteQuietly(e.getValue().data());
            return old;
        });
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp cleanup is best-effort
        }
    }
}
