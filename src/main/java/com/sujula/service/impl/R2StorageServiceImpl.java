package com.sujula.service.impl;

import com.sujula.config.R2Properties;
import com.sujula.exception.BadRequestException;
import com.sujula.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class R2StorageServiceImpl implements StorageService {

    private final S3Client r2Client;
    private final R2Properties r2Properties;

    @Override
    public String upload(MultipartFile file, String folder, String filename) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File must not be empty");
        }

        String key = folder + "/" + filename;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(r2Properties.getBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            r2Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new BadRequestException("Failed to read uploaded file: " + e.getMessage());
        } catch (Exception e) {
            log.error("R2 upload failed for key={}: {}", key, e.getMessage(), e);
            throw new BadRequestException("Storage upload failed: " + e.getMessage());
        }

        // Return the public CDN URL
        return r2Properties.getPublicUrl().stripTrailing() + "/" + key;
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
}
