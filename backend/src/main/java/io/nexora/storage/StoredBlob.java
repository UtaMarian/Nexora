package io.nexora.storage;

public record StoredBlob(String key, String sha256, long sizeBytes) {}
