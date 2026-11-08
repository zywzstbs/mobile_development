package cn.gulupai.server.identity.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.identity.cli.CliOperatorDirectory;
import cn.gulupai.server.identity.persistence.RegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 受控教师注册审核。审核人来自 {@link CliOperatorDirectory} 的可信映射，
 * 不接受请求正文里的 reviewedBy，也不接受 CLI 自由文本 actor。
 */
@Service
public class RegistrationReviewService {

    private final RegistrationRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final CliOperatorDirectory operators;

    public RegistrationReviewService(RegistrationRepository repository,
                                     AuditService auditService,
                                     Clock clock,
                                     CliOperatorDirectory operators) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.operators = operators;
    }

    @Transactional(readOnly = true)
    public List<String> pendingLines() {
        return repository.pending().stream().map(row ->
                row.id() + " user=" + row.userId() + " school=" + row.schoolId()
                        + " revision=" + row.revision()
                        + " application=" + row.teacherApplication()).toList();
    }

    @Transactional
    public String review(String applicationId, int expectedRevision, String decision,
                         String reason) {
        CliOperatorDirectory.Reviewer reviewer = operators.requireCurrent();
        boolean approve = "APPROVE".equals(decision == null ? "" : decision.strip());
        ReviewOutcome outcome = reviewAs(reviewer.subjectId(), applicationId, expectedRevision, decision, reason,
                approve ? "REGISTRATION_APPROVE" : "REGISTRATION_REJECT", row -> {
                });
        return outcome.applicationId() + " " + outcome.storedStatus() + " revision=" + outcome.revision();
    }

    /**
     * 教师申请决定。调用方必须已经确认 actorId 是可信操作者；正文不能指定审核人。
     * 管理接口把库存 ACTIVE 映射为 APPROVED，这里仍写入原申请状态。
     */
    @Transactional
    public ReviewOutcome reviewAs(String actorId, String applicationId, int expectedRevision, String decision,
                                  String reason, String auditAction, ReviewGuard afterLock) {
        String normalizedDecision = decision == null ? "" : decision.strip();
        if (!"APPROVE".equals(normalizedDecision) && !"REJECT".equals(normalizedDecision)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "decision 只能是 APPROVE 或 REJECT");
        }
        String reviewReason = reason == null ? "" : reason.strip();
        if (reviewReason.isEmpty() || reviewReason.length() > 500) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "审核理由需为1至500字");
        }
        RegistrationRepository.ApplicationRow row = repository.lockApplication(applicationId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        if (actorId.equals(row.userId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "不能审核自己的申请");
        }
        if (!"PENDING_REVIEW".equals(row.status()) || !"TEACHER".equals(row.requestedRole())) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        if (row.revision() != expectedRevision) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        if (afterLock != null) {
            afterLock.check(row);
        }
        boolean approve = "APPROVE".equals(normalizedDecision);
        String storedStatus = approve ? "ACTIVE" : "REJECTED";
        String eligibility = approve ? "VERIFIED_ACTIVE" : "REGISTRATION_REJECTED";
        int updated = repository.decide(applicationId, expectedRevision, storedStatus, actorId,
                clock.instant(), reviewReason);
        if (updated != 1) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        if (repository.updatePendingAccount(row.userId(), storedStatus, eligibility) != 1) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "账号已停用或不再处于待审，不能用审核改变登录资格");
        }
        if (approve) {
            repository.insertRole(row.userId(), "TEACHER");
        } else {
            repository.revokeSessions(row.userId(), clock.instant());
        }
        String auditId = auditService.recordReturningId(actorId, auditAction, "REGISTRATION", applicationId,
                expectedRevision, expectedRevision + 1, reviewReason);
        return new ReviewOutcome(applicationId, storedStatus, approve ? "APPROVED" : "REJECTED",
                expectedRevision + 1, auditId);
    }

    @FunctionalInterface
    public interface ReviewGuard {
        void check(RegistrationRepository.ApplicationRow row);
    }

    public record ReviewOutcome(String applicationId, String storedStatus, String apiStatus,
                                int revision, String auditId) {
    }

    @Transactional
    public String setSchoolOpen(String schoolId, boolean enabled) {
        CliOperatorDirectory.Reviewer reviewer = operators.requireCurrent();
        if (!repository.schoolExists(schoolId)) {
            throw new IllegalArgumentException("学校不存在");
        }
        repository.setSchoolRegistration(schoolId, enabled);
        auditService.record(reviewer.subjectId(), "SCHOOL_REGISTRATION", "SCHOOL", schoolId, null, null,
                enabled ? "enabled" : "disabled");
        return schoolId + " registrationEnabled=" + enabled;
    }
}
