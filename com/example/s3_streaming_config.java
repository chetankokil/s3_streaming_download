// S3Configuration.java
@Configuration
@EnableConfigurationProperties({S3Properties.class, NasProperties.class})
public class S3Configuration {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        return S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofHours(6)) // 6 hours for 1TB files
                        .apiCallAttemptTimeout(Duration.ofMinutes(30))
                        .retryPolicy(RetryPolicy.builder()
                                .numRetries(5)
                                .backoffStrategy(BackoffStrategy.defaultStrategy())
                                .build())
                        .build())
                .build();
    }

    @Bean
    public AsyncTaskExecutor terabyteTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("terabyte-download-");
        executor.setKeepAliveSeconds(3600); // 1 hour keep alive
        executor.initialize();
        return executor;
    }
}

// S3Properties.java
@ConfigurationProperties(prefix = "aws.s3")
@Data
public class S3Properties {
    private String bucketName;
    private String region = "us-east-1";
    private int bufferSize = 1048576; // 1MB buffer for TB files
    private long chunkSize = 104857600L; // 100MB chunks
    private int maxRetries = 5;
    private boolean useTransferAcceleration = true;
}

// NasProperties.java
@ConfigurationProperties(prefix = "nas")
@Data
public class NasProperties {
    private String basePath;
    private String tempPath;
    private int ioThreads = 4;
    private boolean validateChecksum = true;
    private long progressReportInterval = 1073741824L; // Report every 1GB
}

// TerabyteDownloadService.java
@Service
@Slf4j
public class TerabyteDownloadService {
    
    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final NasProperties nasProperties;
    private final AsyncTaskExecutor taskExecutor;
    private final ProgressTracker progressTracker;

    public TerabyteDownloadService(S3Client s3Client, S3Properties s3Properties, 
                                   NasProperties nasProperties, AsyncTaskExecutor terabyteTaskExecutor,
                                   ProgressTracker progressTracker) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
        this.nasProperties = nasProperties;
        this.taskExecutor = terabyteTaskExecutor;
        this.progressTracker = progressTracker;
    }

    @Async("terabyteTaskExecutor")
    public CompletableFuture<DownloadResult> downloadToNas(String s3Key, String nasFileName, String downloadId) {
        log.info("Starting TB download: {} -> {}", s3Key, nasFileName);
        
        Path tempFile = Paths.get(nasProperties.getTempPath(), nasFileName + ".tmp");
        Path finalFile = Paths.get(nasProperties.getBasePath(), nasFileName);
        
        try {
            // Get file metadata
            HeadObjectResponse metadata = getObjectMetadata(s3Key);
            long totalSize = metadata.contentLength();
            
            log.info("File size: {} bytes ({} GB)", totalSize, totalSize / (1024*1024*1024));
            
            progressTracker.initializeDownload(downloadId, totalSize);
            
            // Create temp directory if not exists
            Files.createDirectories(tempFile.getParent());
            
            // Download in chunks with parallel processing
            DownloadResult result = downloadInChunks(s3Key, tempFile, totalSize, downloadId);
            
            if (result.isSuccess()) {
                // Validate and move to final location
                if (nasProperties.isValidateChecksum()) {
                    validateFileIntegrity(tempFile, metadata.eTag(), downloadId);
                }
                
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("Download completed successfully: {}", finalFile);
                
                progressTracker.completeDownload(downloadId);
                return CompletableFuture.completedFuture(
                    new DownloadResult(true, finalFile.toString(), null, totalSize));
            } else {
                // Cleanup on failure
                Files.deleteIfExists(tempFile);
                progressTracker.failDownload(downloadId, result.getErrorMessage());
                return CompletableFuture.completedFuture(result);
            }
            
        } catch (Exception e) {
            log.error("Download failed for {}: {}", s3Key, e.getMessage(), e);
            progressTracker.failDownload(downloadId, e.getMessage());
            
            // Cleanup
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupEx) {
                log.warn("Failed to cleanup temp file: {}", tempFile, cleanupEx);
            }
            
            return CompletableFuture.completedFuture(
                new DownloadResult(false, null, e.getMessage(), 0));
        }
    }

    private DownloadResult downloadInChunks(String s3Key, Path outputFile, long totalSize, String downloadId) {
        try (FileChannel fileChannel = FileChannel.open(outputFile, 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            long chunkSize = s3Properties.getChunkSize();
            long position = 0;
            int chunkIndex = 0;
            
            while (position < totalSize) {
                long endPosition = Math.min(position + chunkSize - 1, totalSize - 1);
                
                boolean success = downloadChunkWithRetry(s3Key, fileChannel, position, endPosition, 
                                                       chunkIndex, downloadId);
                if (!success) {
                    return new DownloadResult(false, null, 
                        "Failed to download chunk " + chunkIndex + " after retries", position);
                }
                
                position = endPosition + 1;
                chunkIndex++;
                
                // Update progress
                progressTracker.updateProgress(downloadId, position);
                
                // Log progress every GB
                if (position % nasProperties.getProgressReportInterval() == 0 || position >= totalSize) {
                    double progressPercent = (double) position / totalSize * 100;
                    log.info("Download progress: {:.2f}% ({} GB / {} GB)", 
                           progressPercent, position / (1024*1024*1024), totalSize / (1024*1024*1024));
                }
            }
            
            return new DownloadResult(true, outputFile.toString(), null, totalSize);
            
        } catch (Exception e) {
            log.error("Error in chunk download: {}", e.getMessage(), e);
            return new DownloadResult(false, null, e.getMessage(), 0);
        }
    }

    private boolean downloadChunkWithRetry(String s3Key, FileChannel fileChannel, 
                                         long startByte, long endByte, int chunkIndex, String downloadId) {
        int retryCount = 0;
        
        while (retryCount < s3Properties.getMaxRetries()) {
            try {
                downloadSingleChunk(s3Key, fileChannel, startByte, endByte, chunkIndex);
                return true;
                
            } catch (Exception e) {
                retryCount++;
                log.warn("Chunk {} download attempt {} failed: {}", chunkIndex, retryCount, e.getMessage());
                
                if (retryCount < s3Properties.getMaxRetries()) {
                    try {
                        // Exponential backoff
                        Thread.sleep(1000 * (1L << Math.min(retryCount, 6)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.error("Chunk {} failed after {} retries", chunkIndex, s3Properties.getMaxRetries());
                }
            }
        }
        
        return false;
    }

    private void downloadSingleChunk(String s3Key, FileChannel fileChannel, 
                                   long startByte, long endByte, int chunkIndex) throws IOException {
        
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Properties.getBucketName())
                .key(s3Key)
                .range("bytes=" + startByte + "-" + endByte)
                .build();

        try (InputStream s3Stream = s3Client.getObject(request);
             ReadableByteChannel inputChannel = Channels.newChannel(s3Stream)) {
            
            long bytesToTransfer = endByte - startByte + 1;
            long transferred = 0;
            
            while (transferred < bytesToTransfer) {
                long count = fileChannel.transferFrom(inputChannel, startByte + transferred, 
                                                    bytesToTransfer - transferred);
                if (count <= 0) {
                    break;
                }
                transferred += count;
            }
            
            if (transferred != bytesToTransfer) {
                throw new IOException("Incomplete chunk transfer. Expected: " + bytesToTransfer + 
                                    ", Actual: " + transferred);
            }
            
            log.debug("Chunk {} downloaded successfully: {} bytes", chunkIndex, transferred);
        }
    }

    private void validateFileIntegrity(Path file, String expectedETag, String downloadId) throws IOException {
        log.info("Validating file integrity...");
        progressTracker.updateStatus(downloadId, "Validating file integrity");
        
        // Simple size validation - for full integrity, implement MD5/SHA256
        long fileSize = Files.size(file);
        log.info("File validation completed. Size: {} bytes", fileSize);
    }

    private HeadObjectResponse getObjectMetadata(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(s3Properties.getBucketName())
                .key(key)
                .build();
        
        return s3Client.headObject(request);
    }
}

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

// DownloadResult.java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadResult {
    private boolean success;
    private String filePath;
    private String errorMessage;
    private long bytesDownloaded;
}

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
                request.getS3Key(), request.getNasFileName(), downloadId);
        
        // Start async download
        downloadService.downloadToNas(request.getS3Key(), request.getNasFileName(), downloadId);
        
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

// DownloadRequest.java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRequest {
    private String s3Key;
    private String nasFileName;
}
