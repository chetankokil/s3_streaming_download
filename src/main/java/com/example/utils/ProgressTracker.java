package com.example.utils;

import com.example.models.DownloadProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ProgressTracker.java
@Component
@Slf4j
@EnableScheduling
public class ProgressTracker {
    
    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();
    
    public void initializeDownload(String downloadId, long totalSize) {
        DownloadProgress progress = new DownloadProgress(downloadId, totalSize);
        activeDownloads.put(downloadId, progress);
        log.info("Initialized download tracking: {} ({} GB)", downloadId, totalSize / (1024*1024*1024));
    }
    
    public void updateProgress(String downloadId, long bytesDownloaded) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            activeDownloads.put(downloadId, new DownloadProgress(
                    progress.downloadId(),
                    progress.totalSize(),
                    bytesDownloaded,
                    progress.status(),
                    progress.startedAt(),
                    Instant.now(),
                    progress.completedAt(),
                    progress.errorMessage()
            ));
        }
    }

    public void updateStatus(String downloadId, String status) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            activeDownloads.put(downloadId, new DownloadProgress(
                    progress.downloadId(),
                    progress.totalSize(),
                    progress.bytesDownloaded(),
                    status,
                    progress.startedAt(),
                    Instant.now(),
                    progress.completedAt(),
                    progress.errorMessage()
            ));
        }
    }

    public void completeDownload(String downloadId) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            activeDownloads.put(downloadId, new DownloadProgress(
                    progress.downloadId(),
                    progress.totalSize(),
                    progress.bytesDownloaded(),
                    "COMPLETED",
                    progress.startedAt(),
                    progress.lastUpdated(),
                    Instant.now(),
                    progress.errorMessage()
            ));
        }
    }

    public void failDownload(String downloadId, String errorMessage) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            activeDownloads.put(downloadId, new DownloadProgress(
                    progress.downloadId(),
                    progress.totalSize(),
                    progress.bytesDownloaded(),
                    "FAILED",
                    progress.startedAt(),
                    progress.lastUpdated(),
                    Instant.now(),
                    errorMessage
            ));
        }
    }
    
    public DownloadProgress getProgress(String downloadId) {
        return activeDownloads.get(downloadId);
    }
    
    public Map<String, DownloadProgress> getAllActiveDownloads() {
        return new HashMap<>(activeDownloads);
    }
    
    @Scheduled(fixedRate = 300000) // Clean up every 5 minutes
    public void cleanupCompletedDownloads() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        activeDownloads.entrySet().removeIf(entry -> {
            DownloadProgress progress = entry.getValue();
            return progress.completedAt() != null && progress.completedAt().isBefore(cutoff);
        });
    }
}
