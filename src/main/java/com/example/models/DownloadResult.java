package com.example.models;
// DownloadResult.java
public record DownloadResult(
        boolean success,
        String filePath,
        String errorMessage,
        long bytesDownloaded
) {
}
