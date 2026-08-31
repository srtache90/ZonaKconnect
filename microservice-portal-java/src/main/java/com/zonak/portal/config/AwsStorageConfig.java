package com.zonak.portal.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Configuration
public class AwsStorageConfig {

    @Value("${aws.s3.endpoint-override:http://localhost:4566}")
    private String endpointOverride;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.local-mode:false}")
    private boolean localMode;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Bean
    public S3Client s3Client() {
        if (localMode) {
            return S3Client.builder()
                    .region(Region.of(region))
                    .endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                    )
                    .forcePathStyle(true)
                    .build();
        }

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        if (localMode) {
            return SecretsManagerClient.builder()
                    .region(Region.of(region))
                    .endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                    )
                    .build();
        }

        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
