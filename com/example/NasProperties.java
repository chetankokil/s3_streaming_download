@ConfigurationProperties(prefix = "nas")
@Data
public class NasProperties {
    private String basePath;
    private String tempPath;
    private int ioThreads = 4;
    private boolean validateChecksum = true;
    private long progressReportInterval = 1073741824L; // Report every 1GB
}
