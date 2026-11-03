package io.nexora.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

@Configuration
public class StorageConfig {

    @Bean
    BlobStore blobStore(StorageProperties props) throws IOException {
        String type = props.type() == null ? "filesystem" : props.type();
        return switch (type) {
            case "filesystem" -> new FileSystemBlobStore(Path.of(props.filesystem().root()));
            case "s3" -> {
                StorageProperties.S3 c = props.s3();
                S3ClientBuilder b = S3Client.builder()
                        .region(Region.of(c.region() == null ? "us-east-1" : c.region()))
                        .forcePathStyle(c.pathStyle());
                if (c.endpoint() != null && !c.endpoint().isBlank()) b.endpointOverride(URI.create(c.endpoint()));
                b.credentialsProvider(c.accessKey() != null && !c.accessKey().isBlank()
                        ? StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey()))
                        : DefaultCredentialsProvider.builder().build());
                yield new S3BlobStore(b.build(), c.bucket(), c.createBucket());
            }
            default -> throw new IllegalStateException("Unknown nexora.storage.type: " + type);
        };
    }
}
