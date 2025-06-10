// ProgressTracker.java
@Component
@Slf4j
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
            progress.setBytesDownloaded(bytesDownloaded);
            progress.setLastUpdated(Instant.now());
        }
    }
    
    public void updateStatus(String downloadId, String status) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            progress.setStatus(status);
            progress.setLastUpdated(Instant.now());
        }
    }
    
    public void completeDownload(String downloadId) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            progress.setStatus("COMPLETED");
            progress.setCompletedAt(Instant.now());
        }
    }
    
    public void failDownload(String downloadId, String errorMessage) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null) {
            progress.setStatus("FAILED");
            progress.setErrorMessage(errorMessage);
            progress.setCompletedAt(Instant.now());
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
            return progress.getCompletedAt() != null && progress.getCompletedAt().isBefore(cutoff);
        });
    }
}
