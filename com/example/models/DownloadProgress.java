// DownloadProgress.java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadProgress {
    private String downloadId;
    private long totalSize;
    private long bytesDownloaded;
    private String status = "DOWNLOADING";
    private Instant startedAt = Instant.now();
    private Instant lastUpdated = Instant.now();
    private Instant completedAt;
    private String errorMessage;
    
    public DownloadProgress(String downloadId, long totalSize) {
        this.downloadId = downloadId;
        this.totalSize = totalSize;
        this.bytesDownloaded = 0;
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
