package com.sujula.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.r2")
public class R2Properties {

    private String accountId;
    private String accessKeyId;
    private String secretAccessKey;
    private String bucketName;
    private String publicUrl;
    private String region = "auto";

    public String getEndpoint() {
        return "https://" + accountId + ".r2.cloudflarestorage.com";
    }

    /**
     * True once there is enough here to talk to a bucket.
     *
     * <p>Object storage is needed to upload a product image and for nothing else,
     * so a local checkout should not have to hold Cloudflare credentials to browse
     * a catalogue. The AWS SDK throws on a blank access key while the client is
     * being built, which made this a startup failure rather than a feature that
     * is switched off.
     */
    public boolean isConfigured() {
        return notBlank(accountId) && notBlank(accessKeyId)
                && notBlank(secretAccessKey) && notBlank(bucketName) && notBlank(publicUrl);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
