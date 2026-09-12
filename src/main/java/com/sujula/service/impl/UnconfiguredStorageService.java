package com.sujula.service.impl;

import com.sujula.exceptions.BadRequestException;
import com.sujula.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Stands in for object storage when no bucket is configured.
 *
 * <p>Uploading a product image is the only thing that needs Cloudflare R2, and a
 * local checkout should not need those credentials to browse a catalogue or place
 * an order. The AWS SDK, however, throws while the client is being built if the
 * access key is blank — so an unconfigured deployment could not start at all, and
 * one switched-off feature took the whole application down with it.
 *
 * <p>So the feature is switched off properly instead. Reads that only inspect a
 * URL answer honestly, deletes do nothing, and anything that would actually need
 * a bucket refuses with a message naming the properties to set — a 400 a vendor
 * can act on, rather than a server that will not boot.
 *
 * <p>The condition here is the exact complement of the one on
 * {@link StorageServiceImpl}, so precisely one of the two is ever registered.
 * Both test for a non-empty access key rather than a present one, because these
 * properties are set from the environment with a blank fallback — unset means
 * empty string, not absent. Complementary expressions also keep this
 * deterministic: {@code @ConditionalOnMissingBean} is evaluated during component
 * scanning, where bean-definition order is not guaranteed.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${sujula.r2.access-key-id:}'.length() == 0")
public class UnconfiguredStorageService implements StorageService {

    private static final String MESSAGE =
            "Image storage is not configured on this deployment. Set sujula.r2.account-id, "
                    + "access-key-id, secret-access-key, bucket-name and public-url to enable uploads.";

    public UnconfiguredStorageService() {
        log.warn("[Storage] No object storage configured — product image upload is disabled. {}", MESSAGE);
    }

    @Override
    public String presignUpload(String folder, String filename, String contentType, Duration ttl) {
        throw new BadRequestException(MESSAGE);
    }

    @Override
    public String publicUrl(String folder, String filename) {
        throw new BadRequestException(MESSAGE);
    }

    /**
     * False for everything. With no bucket there is no URL this deployment could
     * have issued, so no image URL can be trusted as its own — which is what the
     * catalogue uses this to decide before attaching one.
     */
    @Override
    public boolean isManagedUrl(String url, String folder) {
        return false;
    }

    /** Nothing was ever stored, so there is nothing to remove. */
    @Override
    public void delete(String publicUrl) {
        // Intentionally empty.
    }
}
