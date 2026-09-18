package com.github.comui520.learnhub.infrastructure.minio;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class MinioConfig {

    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean("minioPresignClient")
    public MinioClient minioPresignClient(MinioProperties properties) {
        String endpoint = StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint()
                : properties.getEndpoint();
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient, MinioProperties properties) {
        return args -> {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists){
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                log.info("Bucket {} created successfully", properties.getBucket());
            }
        };
    }
}
