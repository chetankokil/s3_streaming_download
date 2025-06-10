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
