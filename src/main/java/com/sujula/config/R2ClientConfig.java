package com.sujula.config;

import com.sujula.model.R2Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the object-storage clients, but only when credentials are present.
 *
 * <p>{@code S3Client.builder()} throws on a blank access key as it is built, so
 * without this guard an unconfigured checkout could not start at all — image
 * upload is one feature, and it was taking the whole application with it.
 * Unconfigured, {@link com.sujula.service.impl.UnconfiguredStorageService}
 * stands in and refuses uploads with an explanation.
 *
 * <p>The condition tests for a non-empty value rather than a present one:
 * {@code application.properties} sets these from the environment with a blank
 * fallback, so unset means empty string, not absent — and
 * {@code @ConditionalOnProperty} counts an empty string as present.
 */
@Configuration
@ConditionalOnExpression("'${sujula.r2.access-key-id:}'.length() > 0")
public class R2ClientConfig {

    @Bean
    public S3Client r2Client(R2Properties r2Properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(r2Properties.getEndpoint()))
                .region(Region.of(r2Properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2Properties.getAccessKeyId(), r2Properties.getSecretAccessKey())))
                .build();
    }


    @Bean
    public S3Presigner r2Presigner(R2Properties r2Properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(r2Properties.getEndpoint()))
                .region(Region.of(r2Properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2Properties.getAccessKeyId(), r2Properties.getSecretAccessKey())))
                .build();
    }
}
