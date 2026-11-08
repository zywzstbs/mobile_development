package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.idempotency.ReplayProjector;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.identity.application.RegistrationReviewService;
import cn.gulupai.server.identity.persistence.SessionRepository;
import cn.gulupai.server.platform.api.PlatformRequests;
import cn.gulupai.server.platform.api.PlatformViews;
import cn.gulupai.server.platform.domain.PlatformAction;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import cn.gulupai.server.teaching.application.TeacherClaimReviewService;
import cn.gulupai.server.teaching.persistence.ClassGrantRepository;
import cn.gulupai.server.teaching.persistence.ClassGrantRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * 管理写入锁顺序，所有路径必须一致，不允许“先锁业务行再回头取管理锁”的反向路径：
 * 1. IdempotencyService 对操作者、方法、路径和幂等键的事务级 advisory lock（CLI 无此步）；
 * 2. platform_admin_lock 单行管理约束锁，覆盖 A04、A08、A11、A12、A15、A16 与 CLI 初始化；
 * 3. admin operation 对操作者 + operationId 的事务级 advisory lock；
 * 4. 未提交分支核销本次一次性再认证记录；
 * 5. 锁内重读会话、账号、SUPER_ADMIN 角色、有效授权、能力和范围，取得本次使用的 Access；
 * 6. 业务资源行（用户，或广场后再锁认领/授权），然后原子写入业务、审计与回执。
 * 授权快速拒绝可以在锁外先做，但只有锁内重读的 Access 才能作为提交依据。
 */
@Service
public class PlatformWriteService {

    private final PlatformAuthorizationService authorizationService;
    private final PlatformRepository repository;
    private final AdminReauthenticationService reauthenticationService;
    private final IdempotencyService idempotencyService;
    private final RegistrationReviewService registrationReviewService;
    private final TeacherClaimReviewService teacherClaimReviewService;
    private final ClassGrantRepository classGrantRepository;
    private final SquareRepository squareRepository;
    private final SessionRepository sessionRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformWriteService(PlatformAuthorizationService authorizationService, PlatformRepository repository,
                                AdminReauthenticationService reauthenticationService,
                                IdempotencyService idempotencyService,
                                RegistrationReviewService registrationReviewService,
                                TeacherClaimReviewService teacherClaimReviewService,
                                ClassGrantRepository classGrantRepository, SquareRepository squareRepository,
                                SessionRepository sessionRepository, AuditService auditService,
                                ObjectMapper objectMapper, Clock clock) {
        this.authorizationService = authorizationService;
        this.repository = repository;
        this.reauthenticationService = reauthenticationService;
        this.idempotencyService = idempotencyService;
        this.registrationReviewService = registrationReviewService;
        this.teacherClaimReviewService = teacherClaimReviewService;
        this.classGrantRepository = classGrantRepository;
        this.squareRepository = squareRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void rejectPasswordReset(AuthenticatedUser principal, String userId,
                                    PlatformRequests.PasswordResetRequest request) {
        AdminCommands.CanonicalCommand command = AdminCommands.passwordReset(userId, request);
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, command.action().capability());
        authorizationService.prepare(access, command);
        throw new ApiException(ApiCode.ENDPOINT_NOT_IMPLEMENTED,
                "尚未接入安全交付和一次性兑换渠道，不能创建密码重置请求");
    }

    public PlatformViews.AdminOperationReceipt changeStatus(AuthenticatedUser principal, String userId,
                                                            PlatformRequests.StatusChangeRequest request,
                                                            String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.statusChange(userId, request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> changeStatusLocked(access, command));
    }

    public PlatformViews.AdminOperationReceipt decideTeacher(AuthenticatedUser principal, String applicationId,
                                                             PlatformRequests.DecisionRequest request,
                                                             String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.teacherDecision(applicationId, request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> {
                    var outcome = registrationReviewService.reviewAs(access.actorId(), command.targetId(),
                            command.expectedRevision(), command.decision(), command.note(),
                            "APPROVE".equals(command.decision())
                                    ? "TEACHER_APPLICATION_APPROVED" : "TEACHER_APPLICATION_REJECTED",
                            row -> {
                                var current = authorizationService.require(
                                        access.principal(), command.action().capability());
                                authorizationService.requireSchool(current, row.schoolId());
                            });
                    return new Outcome(outcome.applicationId(), outcome.apiStatus(), outcome.revision(),
                            outcome.auditId());
                });
    }

    public PlatformViews.AdminOperationReceipt decideClaim(AuthenticatedUser principal, String claimId,
                                                           PlatformRequests.DecisionRequest request,
                                                           String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.claimDecision(claimId, request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> {
                    var outcome = teacherClaimReviewService.reviewAs(access.actorId(), command.targetId(),
                            command.expectedRevision(), command.decision(), command.note(),
                            "APPROVE".equals(command.decision())
                                    ? "COURSE_CLAIM_APPROVED" : "COURSE_CLAIM_REJECTED",
                            (schoolId, applicantId, revision) -> {
                                var current = authorizationService.require(
                                        access.principal(), command.action().capability());
                                authorizationService.requireSchool(current, schoolId);
                            });
                    return new Outcome(outcome.claimId(), outcome.claimStatus(), outcome.revision(), outcome.auditId());
                });
    }

    public PlatformViews.AdminOperationReceipt revokeAssistant(AuthenticatedUser principal, String grantId,
                                                               PlatformRequests.RevocationRequest request,
                                                               String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.grantRevocation(grantId, request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> revokeAssistantLocked(access, command));
    }

    public PlatformViews.AdminOperationReceipt grantSuperAdmin(AuthenticatedUser principal,
                                                               PlatformRequests.SuperAdminGrantRequest request,
                                                               String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.superAdminGrant(request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> grantSuperAdminLocked(access, command));
    }

    public PlatformViews.AdminOperationReceipt revokeSuperAdmin(AuthenticatedUser principal, String userId,
                                                                PlatformRequests.RevocationRequest request,
                                                                String operationId, String idempotencyKey, String path) {
        AdminCommands.CanonicalCommand command = AdminCommands.superAdminRevocation(userId, request);
        return execute(principal, command, operationId, request.reauthToken(), idempotencyKey, path,
                access -> revokeSuperAdminLocked(access, command));
    }

    private PlatformViews.AdminOperationReceipt execute(AuthenticatedUser principal,
                                                        AdminCommands.CanonicalCommand command,
                                                        String operationId, String reauthToken,
                                                        String idempotencyKey, String path,
                                                        Function<PlatformAuthorizationService.Access, Outcome> business) {
        String operation = AdminTexts.requireId(operationId, "operationId");
        if (reauthToken == null || reauthToken.isBlank()) {
            throw new ApiException(ApiCode.REAUTH_REQUIRED);
        }
        authorizationService.require(principal, command.action().capability());
        PlatformRequests.BusinessFingerprint fingerprint = AdminCommands.fingerprint(operation, command);
        String digest = reauthenticationService.digest(command);
        return idempotencyService.execute(new IdempotencyRequest(principal, "POST", path, idempotencyKey, fingerprint,
                        List.of(new IdempotencyRequest.ResourceRef(command.action().targetType(), command.targetId()))),
                PlatformViews.AdminOperationReceipt.class,
                () -> requireCurrentScope(principal, command),
                () -> requireCurrentReplayScope(principal, command, operation),
                () -> commit(principal, command, fingerprint, digest, reauthToken, business),
                ReplayProjector.identity());
    }

    /**
     * 新执行只核对“当前操作者资格”和“当前目标可见范围”两类事实，不重放业务前置条件，
     * 也不要求原操作记录已经存在：首次增设/撤销超管在空操作表上同样能通过。
     * 合法撤销后授权不再 ACTIVE、合法审核后不再 PENDING、新增授权后不再 NO_ACTIVE_GRANT，
     * 这些都不应拦住回放或新请求的授权检查。业务摘要冲突仍由调用方比对。
     */
    private PlatformAuthorizationService.Access requireCurrentScope(
            AuthenticatedUser principal, AdminCommands.CanonicalCommand command) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, command.action().capability());
        switch (command.action()) {
            case PASSWORD_RESET_REQUEST -> throw new ApiException(ApiCode.ENDPOINT_NOT_IMPLEMENTED);
            case ACCOUNT_STATUS_CHANGE -> authorizationService.requireSchool(access,
                    repository.findUser(command.targetId())
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND)).schoolId());
            case TEACHER_APPLICATION_DECIDE -> authorizationService.requireSchool(access,
                    repository.findApplication(command.targetId())
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND)).schoolId());
            case COURSE_CLAIM_DECIDE -> authorizationService.requireSchool(access,
                    repository.findClaimTarget(command.targetId())
                            .map(PlatformRepository.TargetRef::schoolId)
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND)));
            case ADMIN_GRANT_REVOKE -> authorizationService.requireSchool(access,
                    classGrantRepository.findById(command.targetId())
                            .map(ClassGrantRow::squareId)
                            .flatMap(squareRepository::findById)
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND))
                            .schoolId());
            case SUPER_ADMIN_GRANT, SUPER_ADMIN_REVOKE -> authorizationService.requireSchool(access,
                    repository.findUser(command.targetId())
                            .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND)).schoolId());
        }
        return access;
    }

    /**
     * 已提交回放在当前范围检查之上，超管授撤还必须追溯原操作对应的授权本身；
     * 只有确认原操作存在时才追溯，避免把首次执行误判为回放。
     */
    private void requireCurrentReplayScope(AuthenticatedUser principal, AdminCommands.CanonicalCommand command,
                                           String operationId) {
        PlatformAuthorizationService.Access access = requireCurrentScope(principal, command);
        if (command.action() == PlatformAction.SUPER_ADMIN_GRANT
                || command.action() == PlatformAction.SUPER_ADMIN_REVOKE) {
            requireOriginalGrantScope(access, principal, operationId);
        }
    }

    /**
     * 超管授撤可能“撤销后重新授予”。回执范围必须追溯原操作对应的授权本身：
     * 用该操作的审计 ID 找到真实 {@code PLATFORM_ADMIN_GRANT} 资源，再按其登记范围核权，
     * 不能用 {@code findActiveGrant(userId)} 把旧操作错套到后来重新授予的授权上。
     */
    private void requireOriginalGrantScope(PlatformAuthorizationService.Access access, AuthenticatedUser principal,
                                           String operationId) {
        PlatformRepository.StoredOperation operation = repository.findOperation(principal.userId(), operationId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        String grantId = repository.auditResourceId(operation.auditId())
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        PlatformRepository.GrantScope scope = repository.grantScope(grantId)
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        if (scope.platform()) {
            if (!access.grant().platform()) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            return;
        }
        if (scope.schools().isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        for (String schoolId : scope.schools()) {
            authorizationService.requireSchool(access, schoolId);
        }
    }

    private PlatformViews.AdminOperationReceipt commit(AuthenticatedUser principal,
                                                       AdminCommands.CanonicalCommand command,
                                                       PlatformRequests.BusinessFingerprint fingerprint,
                                                       String digest, String reauthToken,
                                                       Function<PlatformAuthorizationService.Access, Outcome> business) {
        repository.lockAdminConstraint();
        repository.lockOperation(principal.userId(), fingerprint.operationId());
        var existing = repository.findOperation(principal.userId(), fingerprint.operationId());
        if (existing.isPresent()) {
            if (!existing.get().payloadDigest().equals(digest)) {
                throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REUSED);
            }
            requireCurrentReplayScope(principal, command, fingerprint.operationId());
            return readReceipt(existing.get().receiptJson());
        }
        requireCurrentScope(principal, command);
        String reauthId = reauthenticationService.verifyUnused(principal, reauthToken, fingerprint, digest);
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, command.action().capability());
        Outcome outcome = business.apply(access);
        reauthenticationService.consume(reauthId);
        PlatformViews.AdminOperationReceipt receipt = new PlatformViews.AdminOperationReceipt(
                new PlatformViews.OperationView(fingerprint.operationId(), command.action().name(),
                        command.action().targetType(), command.targetId(), "COMMITTED",
                        outcome.revision(), outcome.auditId()),
                new PlatformViews.ResourceView(outcome.resourceId(), outcome.resourceStatus(), outcome.revision()));
        repository.insertOperation(principal.userId(), fingerprint.operationId(), command.action().name(),
                command.action().targetType(), command.targetId(), digest, outcome.revision(), outcome.auditId(),
                writeReceipt(receipt));
        return receipt;
    }

    private Outcome changeStatusLocked(PlatformAuthorizationService.Access access,
                                       AdminCommands.CanonicalCommand command) {
        PlatformRepository.ManagedUser user = repository.lockUser(command.targetId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        authorizationService.requireSchool(access, user.schoolId());
        if (user.revision() != command.expectedRevision()) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        boolean disable = "DISABLED".equals(command.status());
        if (disable) {
            if (!AccountEligibility.disableEligible(user)) {
                throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "只能停用当前可登录且资格明确的账号");
            }
            requireRecoverabilityPreserved(user);
            if (repository.updateAccount(user.id(), user.revision(), "ACTIVE", false, "DISABLED", true,
                    "ADMIN_DISABLED") != 1) {
                throw new ApiException(ApiCode.REVISION_CONFLICT);
            }
            sessionRepository.revokeAllForUser(user.id(), clock.instant());
            String auditId = auditService.recordReturningId(access.actorId(), "ACCOUNT_DISABLED", "USER", user.id(),
                    user.revision(), user.revision() + 1, command.reason());
            return new Outcome(user.id(), "DISABLED", user.revision() + 1, auditId);
        }
        if (!"DISABLED".equals(user.accountStatus()) || !user.disabled()) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "不能用恢复绕过待审或驳回");
        }
        if (!AccountEligibility.restorationEligible(user)) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "恢复资格来源不明确");
        }
        if (repository.updateAccount(user.id(), user.revision(), "DISABLED", true, "ACTIVE", false,
                AccountEligibility.VERIFIED_ACTIVE) != 1) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        String auditId = auditService.recordReturningId(access.actorId(), "ACCOUNT_RESTORED", "USER", user.id(),
                user.revision(), user.revision() + 1, command.reason());
        return new Outcome(user.id(), "ACTIVE", user.revision() + 1, auditId);
    }

    private Outcome revokeAssistantLocked(PlatformAuthorizationService.Access access,
                                          AdminCommands.CanonicalCommand command) {
        ClassGrantRow located = classGrantRepository.findById(command.targetId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        var square = squareRepository.findByIdForUpdate(located.squareId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        var current = authorizationService.require(access.principal(), command.action().capability());
        authorizationService.requireSchool(current, square.schoolId());
        ClassGrantRow grant = classGrantRepository.findById(command.targetId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        if (grant.revision() != command.expectedRevision() || !"ACTIVE".equals(grant.status())) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        if (classGrantRepository.revoke(grant.id(), grant.revision()) != 1) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        squareRepository.bumpRevisionAndEpoch(square.id());
        String auditId = auditService.recordReturningId(access.actorId(), "ADMIN_GRANT_REVOKED", "CLASS_GRANT",
                grant.id(), grant.revision(), grant.revision() + 1, command.reason());
        return new Outcome(grant.id(), "REVOKED", grant.revision() + 1, auditId);
    }

    private Outcome grantSuperAdminLocked(PlatformAuthorizationService.Access access,
                                          AdminCommands.CanonicalCommand command) {
        if (access.actorId().equals(command.targetId())) {
            throw new ApiException(ApiCode.FORBIDDEN, "不能给自己授予超级管理员");
        }
        PlatformRepository.ManagedUser user = repository.lockUser(command.targetId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        authorizationService.requireSchool(access, user.schoolId());
        PlatformSuperAdminService.assertEligible(user);
        if (repository.findActiveGrant(user.id()).isPresent()) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "该账号已有有效的超级管理员授权");
        }
        boolean platform = access.grant().platform();
        String grantId = repository.insertGrant(user.id(), access.actorId(), clock.instant(), platform,
                platform ? null : user.schoolId());
        String auditId = auditService.recordReturningId(access.actorId(), "SUPER_ADMIN_GRANTED",
                "PLATFORM_ADMIN_GRANT", grantId, null, 1, command.reason());
        return new Outcome(user.id(), "ACTIVE", 1, auditId);
    }

    private Outcome revokeSuperAdminLocked(PlatformAuthorizationService.Access access,
                                           AdminCommands.CanonicalCommand command) {
        PlatformRepository.ManagedUser user = repository.lockUser(command.targetId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        authorizationService.requireSchool(access, user.schoolId());
        PlatformRepository.GrantHead grant = repository.lockActiveGrantByUser(user.id())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        if (grant.revision() != command.expectedRevision()) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        PlatformRepository.ActiveGrant loaded = repository.findGrant(grant.id())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        if (loaded.platform() && !access.grant().platform()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        requireRecoverabilityPreserved(user);
        if (repository.revokeGrant(grant.id(), grant.revision(), access.actorId(), command.reason(),
                clock.instant()) != 1) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        repository.deleteSuperAdminRole(user.id());
        String auditId = auditService.recordReturningId(access.actorId(), "SUPER_ADMIN_REVOKED",
                "PLATFORM_ADMIN_GRANT", grant.id(), grant.revision(), grant.revision() + 1, command.reason());
        return new Outcome(user.id(), "REVOKED", grant.revision() + 1, auditId);
    }

    /**
     * 减少可用性的操作不能把全平台可恢复管理员从正数变成 0。历史库如果已经没有可用
     * 恢复管理员，本检查不会自动补造账号，也不会阻止不恶化恢复能力的动作。
     */
    private void requireRecoverabilityPreserved(PlatformRepository.ManagedUser user) {
        if (repository.isPlatformRecoveryAdmin(user.id())
                && repository.countPlatformRecoveryExcluding(user.id()) == 0) {
            throw new ApiException(ApiCode.LAST_ACTIVE_SUPER_ADMIN);
        }
    }

    private PlatformViews.AdminOperationReceipt readReceipt(String json) {
        try {
            return objectMapper.readValue(json, PlatformViews.AdminOperationReceipt.class);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取管理回执", ex);
        }
    }

    private String writeReceipt(PlatformViews.AdminOperationReceipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存管理回执", ex);
        }
    }

    private record Outcome(String resourceId, String resourceStatus, int revision, String auditId) {
    }
}
