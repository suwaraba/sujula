package com.sujula.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the development seed into the e2e server, once, at startup.
 *
 * <p>Only under the {@code e2e} profile, and there is no second profile this
 * could be switched on for: the class is annotated rather than configured, so
 * turning it on is a code change somebody has to justify in a diff. CLAUDE.md's
 * rule that the seed is never auto-loaded is about the database a person
 * develops against, which this is not — this is a disposable in-memory schema
 * that exists for the length of one test run and is populated before the first
 * request or the run is meaningless.
 *
 * <p><b>Why this exists rather than {@code spring.sql.init}.</b> Spring's script
 * runner executes every statement in the file, and the file opens with
 * {@code START TRANSACTION} — valid MySQL, which is what the seed is written
 * for, and a syntax error in H2 in any mode. Rewriting the seed to suit H2
 * would be the wrong way round: the seed's job is to describe a MySQL database
 * accurately, and this loader's job is to be the one thing that knows the two
 * dialects differ. It skips the three session statements H2 has no grammar for
 * and runs the transaction itself, so the file still gets all-or-nothing
 * semantics — a seed that half-applies leaves a server whose failures nobody
 * can interpret.
 *
 * <p>It runs on {@link ContextRefreshedEvent} rather than on application-ready,
 * and at highest precedence, because two beans write rows of their own when the
 * application is ready — {@code FeatureFlags} inserts any flag missing from the
 * table, and {@code AdminBootstrap} can create an administrator. Both must see
 * the seeded rows already there, or they write duplicates of them.
 */
@Slf4j
@Component
@Profile("e2e")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class E2eSeedLoader implements ApplicationListener<ContextRefreshedEvent> {

    private static final String SEED = "db/seed/dev-seed.sql";

    /**
     * Statements that are MySQL session management and carry no data.
     *
     * <p>{@code START TRANSACTION} and {@code COMMIT} are reproduced by this
     * loader on the JDBC connection instead — skipping them is not dropping the
     * transaction, it is moving it one level up. The third is here because a
     * future edit to the seed might add it, and a seed that silently disabled
     * foreign keys on the way through would hide exactly the ordering mistakes
     * the file's own header says it wants to be told about.
     */
    private static boolean isSessionStatement(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        return upper.startsWith("START TRANSACTION")
                || upper.equals("COMMIT")
                || upper.startsWith("SET FOREIGN_KEY_CHECKS");
    }

    private final DataSource dataSource;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        List<String> statements;
        try {
            statements = split(read());
        } catch (IOException e) {
            throw new IllegalStateException("e2e profile is active but " + SEED + " could not be read", e);
        }

        long started = System.currentTimeMillis();
        int executed = 0;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement jdbc = connection.createStatement()) {
                for (String statement : statements) {
                    if (isSessionStatement(statement)) {
                        continue;
                    }
                    try {
                        jdbc.execute(statement);
                        executed++;
                    } catch (SQLException e) {
                        connection.rollback();
                        throw new IllegalStateException(
                                "Seed statement " + (executed + 1) + " failed, nothing was applied: "
                                        + firstLine(statement) + " — " + e.getMessage(), e);
                    }
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load " + SEED + " into the e2e database", e);
        }

        log.info("e2e seed applied: {} statements in {}ms", executed, System.currentTimeMillis() - started);
    }

    private String read() throws IOException {
        try (var in = new ClassPathResource(SEED).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits the file on semicolons that are not inside a string literal.
     *
     * <p>Naive splitting is wrong here for a reason the seed contains on
     * purpose: return policies, store descriptions and dispute notes are prose,
     * and prose contains semicolons. Apostrophes are doubled inside those
     * literals, which is why a doubled quote advances past both characters
     * rather than toggling twice — toggling twice happens to give the same
     * answer, but only until a literal ends on one.
     */
    private static List<String> split(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        String body = withoutComments.toString();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
            }
            if (c == ';' && !inString) {
                add(statements, current);
            } else {
                current.append(c);
            }
        }
        add(statements, current);
        return statements;
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private static String firstLine(String statement) {
        int newline = statement.indexOf('\n');
        String line = newline < 0 ? statement : statement.substring(0, newline);
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }
}
