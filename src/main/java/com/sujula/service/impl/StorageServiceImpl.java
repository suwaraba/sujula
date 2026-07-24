package com.sujula.service.impl;

import com.sujula.model.R2Properties;
import com.sujula.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final S3Client r2Client;
    private final S3Presigner r2Presigner;
    private final R2Properties r2Properties;

    @Override
    public String presignUpload(String folder, String filename, String contentType, Duration ttl) {
        String key = buildKey(folder, filename);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(r2Properties.getBucketName())
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = r2Presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    @Override
    public String publicUrl(String folder, String filename) {
        return r2Properties.getPublicUrl().stripTrailing() + "/" + buildKey(folder, filename);
    }

    @Override
    public boolean isManagedUrl(String url, String folder) {
        if (url == null) return false;
        String prefix = r2Properties.getPublicUrl().stripTrailing() + "/" + folder + "/";
        return url.startsWith(prefix);
    }

    @Override
    public void delete(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) return;

        // Derive the object key from the public URL
        String prefix = r2Properties.getPublicUrl().stripTrailing() + "/";
        if (!publicUrl.startsWith(prefix)) {
            log.warn("Cannot delete R2 object — URL does not match public base: {}", publicUrl);
            return;
        }

        String key = publicUrl.substring(prefix.length());
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(r2Properties.getBucketName())
                    .key(key)
                    .build());
        } catch (Exception e) {
            // Log but do not throw — deletion failures should not block business operations
            log.warn("R2 delete failed for key={}: {}", key, e.getMessage());
        }
    }

    private String buildKey(String folder, String filename) {
        return folder + "/" + filename;
    }
}