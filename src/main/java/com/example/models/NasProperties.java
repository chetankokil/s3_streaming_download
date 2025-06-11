package com.example.models;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nas")
public record NasProperties(
        String basePath,
        String tempPath,
        int ioThreads,
        boolean validateChecksum,
        long progressReportInterval
) {
    public NasProperties {
        if (ioThreads == 0) ioThreads = 4;
        if (progressReportInterval == 0) progressReportInterval = 1073741824L; // Report every 1GB
        validateChecksum = true;
    }

    public NasProperties() {
        this(null, null, 4, true, 1073741824L);
    }
}
