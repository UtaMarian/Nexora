package io.nexora.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores blobs under a root directory (local disk or an NFS mount). */
public class FileSystemBlobStore implements BlobStore {
    private final Path root;

    public FileSystemBlobStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public StoredBlob put(Path file) throws IOException {
        String sha = Hashing.sha256(file);
        String key = BlobStore.keyFor(sha);
        Path target = resolve(key);
        long size = Files.size(file);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                if (!Files.exists(target)) throw e; // lost a race with an identical upload: fine
            }
        }
        return new StoredBlob(key, sha, size);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }
}
