package com.example.models;


import org.springframework.boot.context.properties.ConfigurationProperties;

// S3Properties.java
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
        String bucketName,
        String region,
        int bufferSize,
        long chunkSize,
        int maxRetries,
        boolean useTransferAcceleration
) {
    public S3Properties {
        if (region == null) region = "us-east-1";
        if (bufferSize == 0) bufferSize = 1048576; // 1MB buffer for TB files
        if (chunkSize == 0) chunkSize = 104857600L; // 100MB chunks
        if (maxRetries == 0) maxRetries = 5;
        useTransferAcceleration = true;
    }

    public S3Properties() {
        this(null, null, 0, 0, 0, true);
    }
}
