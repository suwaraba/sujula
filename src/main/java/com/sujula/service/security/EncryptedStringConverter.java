package com.sujula.service.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Puts {@link FieldEncryptionService} in the path of a column.
 *
 * <p>At the mapping rather than in a service, because this is the only place it
 * cannot be forgotten. A service that encrypts on the way in leaves every other
 * write path — an admin tool, a migration, a repository method somebody adds
 * next year — free to put plaintext into the same column, and nothing about the
 * column says it should not.
 *
 * <p>Not {@code autoApply}: which columns are secret is a decision per field,
 * and a converter that grabbed every String would encrypt the store names too.
 *
 * <h2>Why the dependency is not injected</h2>
 *
 * <p>Hibernate builds converters through Spring's bean container, so a
 * constructor parameter does work — inside a full application context. It stops
 * working in a JPA slice test, which maps every entity and scans no services,
 * and the failure is not confined to tests of this feature: every
 * {@code @DataJpaTest} in the codebase fails to start, because they all map
 * {@code BankAccount}. Making an unrelated repository test import a bank-details
 * encryption service to load its context is the wrong coupling.
 *
 * <p>So the service publishes itself to {@link FieldEncryptionService#shared()}
 * as it is created, and this reads it from there. Static state, deliberately,
 * and with the one property that makes it safe: when nothing has been published
 * it <em>fails closed</em>. A write throws rather than storing plaintext, which
 * is the same answer this system gives when no key is configured at all.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        FieldEncryptionService crypto = FieldEncryptionService.shared();
        if (crypto == null) {
            // Never a silent pass-through. A context that maps this column and
            // has no encryption service has no business writing to it.
            throw new IllegalStateException(
                    "No encryption service is available, so an encrypted field cannot be written. "
                    + "In a JPA slice test, add @Import(FieldEncryptionService.class).");
        }
        return crypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        FieldEncryptionService crypto = FieldEncryptionService.shared();
        // Reading is the forgiving direction: a value with no version marker was
        // never encrypted, so there is nothing to decrypt and no key needed.
        return crypto == null ? FieldEncryptionService.passThroughIfPlain(dbData) : crypto.decrypt(dbData);
    }
}
