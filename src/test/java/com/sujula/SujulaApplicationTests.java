package com.sujula;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application.
 *
 * <p>The cheapest test in the suite and, for a codebase this size, the one that
 * finds the most. Spring Data parses every {@code @Query} at startup, Hibernate
 * resolves every association, and the security chain, the filters and the
 * scheduled workers all have to wire — so an entity that maps to a field which
 * does not exist, or a JPQL join to the wrong side of a relationship, fails here
 * rather than on the first request that happens to touch it. Mock-based unit
 * tests cannot see any of that: they stub the repositories out.
 *
 * <p>Runs against H2, because needing a live MySQL is why it used to be skipped.
 */
@SpringBootTest
@ActiveProfiles("test")
class SujulaApplicationTests {

    @Test
    void contextLoads() {
    }

}
