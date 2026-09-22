package com.sujula.service;

import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.AuditLogRepository;
import com.sujula.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who the trail says did it. */
class AuditServiceImplTest {

    private AuditLogRepository auditLogRepository;
    private AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        GeoService geoService = mock(GeoService.class);
        when(geoService.getClientIp(any())).thenReturn("41.222.10.7");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(i -> i.getArgument(0));

        service = new AuditServiceImpl(auditLogRepository, geoService);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void stampsTheEntryWithTheAdminWhoActedAndWhereFrom() {
        signIn(adminUser());

        service.record(AuditAction.USER_BLOCKED, "USER", 42L, "buyer@sujula.gm", "Account blocked", "Chargebacks");

        AuditLog entry = captured();
        assertEquals(AuditAction.USER_BLOCKED, entry.getAction());
        assertEquals("admin@sujula.gm", entry.getActorEmail());
        assertEquals("Ada Admin", entry.getActorName());
        assertEquals("USER", entry.getTargetType());
        assertEquals(42L, entry.getTargetId());
        assertEquals("buyer@sujula.gm", entry.getTargetLabel());
        assertEquals("Chargebacks", entry.getDetails());
        assertEquals("41.222.10.7", entry.getIpAddress());
    }

    @Test
    void keepsTheActorAsTextSoTheEntryOutlivesTheAccount() {
        User admin = adminUser();
        signIn(admin);

        service.record(AuditAction.USER_PURGED, "USER", 42L, "buyer@sujula.gm", "Account permanently deleted");

        AuditLog entry = captured();
        // The relation may point at a row that is later deleted; the snapshot is
        // what an auditor actually reads a year from now.
        assertEquals(admin, entry.getActor());
        assertEquals("admin@sujula.gm", entry.getActorEmail());
    }

    @Test
    void aSystemActionHasNoActor() {
        service.recordSystemAction(AuditAction.ADMIN_BOOTSTRAPPED, "USER", 1L, "boss@sujula.gm",
                "First administrator created", null);

        AuditLog entry = captured();
        assertNull(entry.getActor());
        assertNull(entry.getActorEmail());
        assertNull(entry.getIpAddress(), "a startup task has no caller to attribute");
    }

    private AuditLog captured() {
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(auditLogRepository).save(saved.capture());
        return saved.getValue();
    }

    private static User adminUser() {
        User admin = new User();
        admin.setId(7L);
        admin.setEmail("admin@sujula.gm");
        admin.setFirstName("Ada");
        admin.setLastName("Admin");
        admin.setRole(UserRole.ADMIN);
        return admin;
    }

    private static void signIn(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
