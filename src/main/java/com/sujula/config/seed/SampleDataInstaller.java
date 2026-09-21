package com.sujula.config.seed;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the sample-data stages, all in one transaction.
 *
 * <p>One transaction on purpose. The dataset only makes sense whole: a shipment
 * whose vendor order was never written is not a partial success, it is a row
 * that will fail the first query that joins through it. Either every stage
 * applies or none does.
 *
 * <p>Separate from {@link SampleDataSeeder} because the seeder is an event
 * listener, and a listener that annotated its own method {@code @Transactional}
 * would be calling it from inside the same object — past the proxy, so with no
 * transaction at all. Splitting the bean is what makes the annotation mean
 * something.
 */
@Slf4j
@Component
public class SampleDataInstaller {

    /** What a stage reports back when the database was already populated. */
    public record Outcome(boolean skipped, String detail, int rows, int entities) {

        static Outcome skipped(String detail) {
            return new Outcome(true, detail, 0, 0);
        }

        static Outcome applied(int rows, int entities) {
            return new Outcome(false, null, rows, entities);
        }
    }

    @PersistenceContext
    private EntityManager em;

    private final PasswordEncoder passwordEncoder;
    private final List<SeedStage> stages;

    public SampleDataInstaller(PasswordEncoder passwordEncoder,
                               PeopleStage people,
                               StoreStage store,
                               CatalogueStage catalogue,
                               LogisticsStage logistics,
                               MoneyStage money,
                               CommerceStage commerce,
                               FulfilmentStage fulfilment,
                               AftersalesStage aftersales,
                               FinanceStage finance,
                               PlatformStage platform) {
        this.passwordEncoder = passwordEncoder;
        // Listed rather than injected as a collection: the order is the whole
        // point, and a List<SeedStage> ordered by whatever the container felt
        // like would break on the first foreign key.
        this.stages = List.of(people, store, catalogue, logistics, money,
                commerce, fulfilment, aftersales, finance, platform);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome install() {
        Long existingUsers = em.createQuery("select count(u) from User u", Long.class).getSingleResult();
        if (existingUsers != null && existingUsers > 0) {
            return Outcome.skipped("the database already holds " + existingUsers
                    + " user(s), so nothing was written. Drop the schema to seed it again.");
        }

        SeedCatalogue catalogue = new SeedCatalogue(em, passwordEncoder, LocalDateTime.now());
        for (SeedStage stage : stages) {
            int before = catalogue.rows();
            try {
                stage.seed(catalogue);
                catalogue.flush();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Sample data stage '" + stage.name() + "' failed: " + e.getMessage(), e);
            }
            log.info("[Sample data]   {} — {} rows", stage.name(), catalogue.rows() - before);
        }
        return Outcome.applied(catalogue.rows(), catalogue.entityCount());
    }
}
