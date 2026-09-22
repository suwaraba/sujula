package com.sujula.dto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every response an idempotent endpoint can return, this application must be
 * able to read back.
 *
 * <p>Which sounds like a property nobody needs — a response goes out and is
 * never parsed again. It is needed in exactly one place, and it is a place where
 * being wrong costs money: {@code IdempotencyService} stores the serialised
 * response against the key and replays it when the client retries. A type
 * Jackson can write and cannot read turns the retry into a 500 — on the one
 * request that was retried precisely because the first answer went missing.
 *
 * <p>{@code CartResponse} was that type. Lombok's {@code @Builder} suppresses
 * the default constructor, so POST /carts/&#123;token&#125;/items — whose own
 * description reads "send an Idempotency-Key so a retried tap does not add the
 * item twice" — answered 500 to the second tap. Every other idempotent response
 * on this platform is a record, which Jackson builds from its components, so it
 * was the only one that could break and nothing else was going to notice.
 *
 * <p><b>The list is read out of the source rather than typed here.</b> A typed
 * list is a list that goes stale on the next endpoint somebody makes
 * idempotent, and the failure it would miss is the same 500 in a different
 * place. So this test greps the call sites for the {@code Class} literal each
 * one hands to {@code execute}, and holds every one of them to being readable.
 * It also fails when it finds no call sites at all, because a scan that has
 * silently stopped matching is worse than no scan.
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotentResponseRoundTripTest {

    /**
     * {@code idempotency.execute(scope, key, request, status, Something.class, ...)}
     *
     * <p>Matched on the class literal in the fifth argument rather than on the
     * whole call, because the calls are wrapped across lines in every possible
     * shape and a pattern that insisted on the arrangement would match none of
     * them.
     */
    private static final Pattern EXECUTE_CALL =
            Pattern.compile("idempotency\\.execute\\(", Pattern.DOTALL);
    private static final Pattern CLASS_LITERAL =
            Pattern.compile("([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\.class");

    @Autowired private ObjectMapper json;

    @Test
    void everyIdempotentResponseTypeCanBeReadBack() throws IOException {
        Set<String> typeNames = idempotentResponseTypeNames();

        assertTrue(typeNames.size() >= 20,
                "found only " + typeNames.size() + " idempotent response types, which means "
                        + "this test has stopped matching the call sites rather than that the "
                        + "endpoints went away: " + typeNames);

        List<String> unreadable = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (String simpleName : typeNames) {
            Class<?> type = resolve(simpleName);
            if (type == null) {
                unresolved.add(simpleName);
                continue;
            }
            if (type.isRecord()) {
                // Jackson builds a record from its components; it cannot have
                // the defect this test is about.
                continue;
            }
            try {
                json.readValue("{}", type);
            } catch (Exception e) {
                unreadable.add(type.getName() + " — " + e.getClass().getSimpleName() + ": "
                        + firstLine(e.getMessage()));
            }
        }

        assertTrue(unresolved.isEmpty(),
                "could not load these response types, so they were not checked: " + unresolved);

        assertTrue(unreadable.isEmpty(),
                "These types are returned by an idempotent endpoint and cannot be deserialised, "
                        + "so replaying a stored response answers 500 on the retry the key exists "
                        + "for. Beside the @Builder, add @NoArgsConstructor and "
                        + "@AllArgsConstructor(access = AccessLevel.PRIVATE) — private, or "
                        + "Jackson takes the all-args constructor as a creator and fails on the "
                        + "first primitive:\n  " + String.join("\n  ", unreadable));
    }

    /** Every {@code X.class} that appears inside an {@code idempotency.execute(...)} call. */
    private Set<String> idempotentResponseTypeNames() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(main), "expected to be run from the module root");

        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = EXECUTE_CALL.matcher(source);
                while (call.find()) {
                    // The arguments run to the closing bracket of the lambda; a
                    // generous window is enough, because the class literal is
                    // always the fifth argument and nothing else in the call is
                    // a class literal.
                    int from = call.end();
                    int to = Math.min(source.length(), from + 400);
                    Matcher literal = CLASS_LITERAL.matcher(source.substring(from, to));
                    if (literal.find()) {
                        names.add(literal.group(1));
                    }
                }
            }
        }
        return names;
    }

    /**
     * Finds a class from the name as it is written at the call site.
     *
     * <p>Most are nested — {@code AdminUserResponses.UserCreated} is written as
     * {@code UserCreated.class} because the outer type is imported — so the
     * simple name has to be searched for across the response packages. Reflection
     * on a name alone cannot do that, so the walk over the source files that
     * found the call sites is reused to find the declaration.
     */
    private Class<?> resolve(String writtenName) throws IOException {
        String simple = writtenName.substring(writtenName.lastIndexOf('.') + 1);

        // DTOs first, and that is not a tidiness preference. `Address.class` and
        // `Coupon.class` at a call site mean AddressResponses.Address and
        // PromotionResponses.Coupon — but com.sujula.model has an entity of
        // each name, and matching one of those would have this test asserting
        // that a JPA entity round trips, which is neither true nor the point.
        Path main = Path.of("src", "main", "java");
        List<Path> candidates;
        try (Stream<Path> files = Files.walk(main)) {
            candidates = files.filter(f -> f.toString().endsWith(".java"))
                    .sorted((a, b) -> Integer.compare(rank(a), rank(b)))
                    .toList();
        }

        for (Path file : candidates) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!Pattern.compile("\\b(record|class|interface|enum)\\s+" + Pattern.quote(simple) + "\\b")
                    .matcher(source).find()) {
                continue;
            }
            String packageName = packageOf(source);
            String outer = file.getFileName().toString().replace(".java", "");
            for (String candidate : List.of(
                    packageName + "." + outer + "$" + simple,
                    packageName + "." + simple)) {
                try {
                    return Class.forName(candidate);
                } catch (ClassNotFoundException ignored) {
                    // try the next shape
                }
            }
        }
        return null;
    }

    /** Response DTOs, then the rest. Lower sorts first. */
    private static int rank(Path file) {
        String path = file.toString().replace('\\', '/');
        if (path.contains("/dto/response/")) {
            return 0;
        }
        return path.contains("/dto/") ? 1 : 2;
    }

    private static String packageOf(String source) {
        Matcher matcher = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(no message)";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
