package io.nexora.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Stores blobs in any S3-compatible object store. */
public class S3BlobStore implements BlobStore {
    private final S3Client s3;
    private final String bucket;

    public S3BlobStore(S3Client s3, String bucket, boolean createBucket) {
        this.s3 = s3;
        this.bucket = bucket;
        if (createBucket) ensureBucket();
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public StoredBlob put(Path file) throws IOException {
        String sha = Hashing.sha256(file);
        String key = BlobStore.keyFor(sha);
        long size = java.nio.file.Files.size(file);
        if (!exists(key)) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
        }
        return new StoredBlob(key, sha, size);
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }
}
