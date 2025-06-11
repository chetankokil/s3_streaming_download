package com.example.controllers;

import com.example.models.DownloadProgress;
import com.example.models.DownloadRequest;
import com.example.services.TerabyteDownloadService;
import com.example.utils.ProgressTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// TerabyteDownloadController.java
@RestController
@RequestMapping("/api/terabyte")
@Slf4j
public class TerabyteDownloadController {

    private final TerabyteDownloadService downloadService;
    private final ProgressTracker progressTracker;

    public TerabyteDownloadController(TerabyteDownloadService downloadService, 
                                    ProgressTracker progressTracker) {
        this.downloadService = downloadService;
        this.progressTracker = progressTracker;
    }

    @PostMapping("/download")
    public ResponseEntity<Map<String, String>> startDownload(@RequestBody DownloadRequest request) {
        String downloadId = UUID.randomUUID().toString();
        
        log.info("Starting terabyte download: {} -> {} (ID: {})", 
                request.s3Key(), request.nasFileName(), downloadId);
        
        // Start async download
        downloadService.downloadToNas(request.s3Key(), request.nasFileName(), downloadId);
        
        Map<String, String> response = new HashMap<>();
        response.put("downloadId", downloadId);
        response.put("status", "STARTED");
        response.put("message", "Download started successfully");
        
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/progress/{downloadId}")
    public ResponseEntity<DownloadProgress> getProgress(@PathVariable String downloadId) {
        DownloadProgress progress = progressTracker.getProgress(downloadId);
        
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/progress")
    public ResponseEntity<Map<String, DownloadProgress>> getAllProgress() {
        return ResponseEntity.ok(progressTracker.getAllActiveDownloads());
    }

    @DeleteMapping("/cancel/{downloadId}")
    public ResponseEntity<Map<String, String>> cancelDownload(@PathVariable String downloadId) {
        // Implementation for canceling downloads would go here
        Map<String, String> response = new HashMap<>();
        response.put("message", "Cancel functionality not implemented yet");
        return ResponseEntity.ok(response);
    }
}
