package com.sujula.service.admin;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminPlatformRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminPlatformResponses;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.user.User;

/**
 * Deciding disputes, telling people things, and the switches behind the
 * platform itself.
 *
 * <p>Support works the dispute queue — reading it, taking one, writing internal
 * notes, and arranging a call — and cannot decide one. Deciding moves money,
 * and money is an administrator's, with their own password on top of their
 * session.
 */
public interface AdminPlatformService {

    // ── Disputes ─────────────────────────────────────────────────────────────

    PagedResponse<AdminPlatformResponses.DisputeRow> queue(
            DisputeStatus status, boolean openOnly, Long assigneeUserId, boolean unassignedOnly,
            Long vendorId, DisputeReason reason, boolean overdueOnly, Pageable pageable);

    AdminPlatformResponses.DisputeRow readDispute(Long disputeId);

    AdminPlatformResponses.DisputeRow assign(
            User staff, Long disputeId, AdminPlatformRequests.AssignDispute request);

    AdminPlatformResponses.NoteAdded addNote(
            User staff, Long disputeId, AdminPlatformRequests.AddNote request);

    AdminPlatformResponses.DisputeDecided resolve(
            User staff, Long disputeId, AdminPlatformRequests.ResolveDispute request);

    AdminPlatformResponses.CallbackRow requestCallback(
            User staff, Long disputeId, AdminPlatformRequests.RequestCallback request);

    AdminPlatformResponses.CallbackRow recordCallback(
            User staff, Long callbackId, AdminPlatformRequests.RecordCallback request);

    PagedResponse<AdminPlatformResponses.CallbackRow> outstandingCallbacks(Pageable pageable);

    // ── Comms ────────────────────────────────────────────────────────────────

    AdminPlatformResponses.AnnouncementSent announce(
            User staff, AdminPlatformRequests.Announce request);

    AdminPlatformResponses.NotificationSent notify(
            User staff, AdminPlatformRequests.SendNotification request);

    // ── Platform ─────────────────────────────────────────────────────────────

    PagedResponse<AdminPlatformResponses.AuditRow> auditLog(
            Long actorUserId, AuditAction action, String targetType, Long targetId,
            String q, LocalDate from, LocalDate to, Pageable pageable);

    List<AdminPlatformResponses.FlagRow> flags();

    AdminPlatformResponses.FlagRow setFlag(
            User staff, String flagKey, AdminPlatformRequests.SetFeatureFlag request);

    List<AdminPlatformResponses.JobRow> jobs();

    PagedResponse<AdminPlatformResponses.JobRow> jobHistory(
            String jobName, JobRunStatus status, Pageable pageable);

    AdminPlatformResponses.JobTriggered triggerJob(
            User staff, String jobName, AdminPlatformRequests.TriggerJob request);

    AdminPlatformResponses.Dashboard dashboard();
}
