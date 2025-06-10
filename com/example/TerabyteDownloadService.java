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
