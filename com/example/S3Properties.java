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
