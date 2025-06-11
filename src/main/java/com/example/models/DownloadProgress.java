package com.example.models;

import java.time.Duration;
import java.time.Instant;

public record DownloadProgress(
        String downloadId,
        long totalSize,
        long bytesDownloaded,
        String status,
        Instant startedAt,
        Instant lastUpdated,
        Instant completedAt,
        String errorMessage
) {
    public DownloadProgress {
        if (status == null) status = "DOWNLOADING";
        if (startedAt == null) startedAt = Instant.now();
        if (lastUpdated == null) lastUpdated = Instant.now();
    }

    public DownloadProgress(String downloadId, long totalSize) {
        this(downloadId, totalSize, 0, "DOWNLOADING", Instant.now(), Instant.now(), null, null);
    }
    
    public double getProgressPercentage() {
        return totalSize > 0 ? (double) bytesDownloaded / totalSize * 100 : 0;
    }
    
    public long getDownloadSpeed() {
        Duration elapsed = Duration.between(startedAt, lastUpdated);
        long seconds = elapsed.getSeconds();
        return seconds > 0 ? bytesDownloaded / seconds : 0;
    }
}
