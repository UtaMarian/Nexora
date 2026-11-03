package io.nexora.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Content-addressed binary storage. Keys are derived from the SHA-256 of the content, so identical
 * files are stored once and versions can share blobs. Implementations: local filesystem / NFS, and any
 * S3-compatible store (MinIO, Ceph, NetApp StorageGRID, Dell ECS, AWS).
 */
public interface BlobStore {

    /** Stores the file and returns its content address. Idempotent for identical content. */
    StoredBlob put(Path file) throws IOException;

    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;

    boolean exists(String key) throws IOException;

    static String keyFor(String sha256) {
        return "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256;
    }
}
