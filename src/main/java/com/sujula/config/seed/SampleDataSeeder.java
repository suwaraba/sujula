package com.sujula.config.seed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Puts a complete, coherent sample dataset into the database at startup.
 *
 * <p>This is the Java counterpart of {@code db/seed/dev-seed.sql}. The SQL file
 * describes a MySQL database and has to be run by hand; this runs itself, against
 * whatever database the application happens to have booted with — MySQL, H2, a
 * container a test started — because the thing being sampled is the object model
 * rather than a dialect. Every persistent entity in the application gets rows
 * here, and every entity that has states gets a row in each of them: an order
 * that is still pending and one that was refunded, a dispute that is open and one
 * resolved for the vendor, a driver who was rejected and one who is out
 * delivering. Sampling a single happy row tells you almost nothing; the point of
 * this dataset is that the awkward rows are already there.
 *
 * <h2>Turning it on</h2>
 *
 * <pre>
 *   mvn -o spring-boot:run -Dspring-boot.run.profiles=sample
 *   # or, on any profile:
 *   mvn -o spring-boot:run -Dspring-boot.run.arguments=--sujula.sample-data.enabled=true
 * </pre>
 *
 * <p>Off unless asked for. Sample rows appearing in a database by surprise is
 * worse than typing one flag, and this one shares that rule with the SQL seed.
 *
 * <h2>Why it never runs twice</h2>
 *
 * <p>It writes nothing when the database already holds users. Unlike the SQL
 * seed it does <em>not</em> delete first: a seeder that truncates on every start
 * is one misconfigured environment away from erasing work somebody wanted. To
 * apply it again, drop the schema — that is a decision worth making out loud,
 * and on the e2e profile it happens anyway, because the database is in memory
 * and lives only as long as the run.
 *
 * <h2>Why {@link ContextRefreshedEvent} and not application-ready</h2>
 *
 * <p>Two beans write rows of their own once the application is ready:
 * {@code FeatureFlags} inserts any declared flag with no row, and
 * {@code AdminBootstrap} creates or promotes an administrator. Both must see the
 * sampled rows already in place or they duplicate them — which is the same
 * reason {@code E2eSeedLoader} runs here, at the same precedence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SampleDataSeeder implements ApplicationListener<ContextRefreshedEvent> {

    private final SampleDataInstaller installer;

    @Value("${sujula.sample-data.enabled:false}")
    private boolean enabled;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!enabled) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            SampleDataInstaller.Outcome outcome = installer.install();
            if (outcome.skipped()) {
                log.info("[Sample data] Skipped: {}", outcome.detail());
                return;
            }
            log.info("[Sample data] {} rows across {} entities in {}ms",
                    outcome.rows(), outcome.entities(), System.currentTimeMillis() - started);
            log.info("[Sample data] Sign in as any seeded account with the password '{}'",
                    SeedCatalogue.SAMPLE_PASSWORD);
        } catch (RuntimeException e) {
            // Loudly, and fatally. A half-applied sample dataset is worse than
            // none: every later failure has to be re-examined against the
            // question of whether the data was ever really there.
            throw new IllegalStateException(
                    "Sample data could not be installed, and nothing was applied: " + e.getMessage(), e);
        }
    }
}
