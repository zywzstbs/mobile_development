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
        String normalizedDecision = decision == null ? "" : decision.strip();
        if (!"APPROVE".equals(normalizedDecision) && !"REJECT".equals(normalizedDecision)) {
            throw new IllegalArgumentException("decision 只能是 APPROVE 或 REJECT");
        }
        String reviewReason = reason == null ? "" : reason.strip();
        if (reviewReason.isEmpty() || reviewReason.length() > 500) {
            throw new IllegalArgumentException("审核理由需为1至500字");
        }
        RegistrationRepository.ApplicationRow row = repository.lockApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("注册申请不存在"));
        if (reviewer.subjectId().equals(row.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "不能审核自己的申请");
        }
        if (!"PENDING_REVIEW".equals(row.status()) || !"TEACHER".equals(row.requestedRole())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        boolean approve = "APPROVE".equals(normalizedDecision);
        String status = approve ? "ACTIVE" : "REJECTED";
        int updated = repository.decide(applicationId, expectedRevision, status, reviewer.subjectId(),
                clock.instant(), reviewReason);
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (repository.updateAccountStatus(row.userId(), status) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (approve) {
            repository.insertRole(row.userId(), "TEACHER");
        } else {
            repository.revokeSessions(row.userId(), clock.instant());
        }
        auditService.record(reviewer.subjectId(), approve ? "REGISTRATION_APPROVE" : "REGISTRATION_REJECT",
                "REGISTRATION", applicationId, expectedRevision, expectedRevision + 1, reviewReason);
        return applicationId + " " + status + " revision=" + (expectedRevision + 1);
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
