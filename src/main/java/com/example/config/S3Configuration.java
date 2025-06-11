package com.example.config;

import com.example.models.S3Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

@Configuration
public class S3Configuration {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
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