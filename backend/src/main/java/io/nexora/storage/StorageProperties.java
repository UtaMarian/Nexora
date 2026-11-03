package io.nexora.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexora.storage")
public record StorageProperties(String type, FileSystem filesystem, S3 s3) {

    public record FileSystem(String root) {}

    public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey,
                     boolean pathStyle, boolean createBucket) {}
}
