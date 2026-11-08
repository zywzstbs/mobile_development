package cn.gulupai.server.teaching.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.course.persistence.VerifiedCourseRow;
import cn.gulupai.server.identity.domain.IdentityStatus;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.teaching.persistence.TeacherClaimRow;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 受控管理员 CLI 的教师认领审核。普通 App 不能调用；审核通过才建立当前教师权属。
 */
@Service
public class TeacherClaimReviewService {

    private final TeachingRepository teachingRepository;
    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public TeacherClaimReviewService(TeachingRepository teachingRepository,
                                     SquareRepository squareRepository,
                                     VerifiedCourseRepository verifiedCourseRepository,
                                     UserRepository userRepository,
                                     AuditService auditService,
                                     TransactionTemplate transactionTemplate) {
        this.teachingRepository = teachingRepository;
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
    }

    public ReviewResult review(String claimId, String decision, String reason) {
        ClaimDecision result = transactionTemplate.execute(status -> reviewAs(
                "cli", claimId, null, decision, reason,
                "APPROVE".equals(decision) ? "TEACHER_CLAIM_APPROVE" : "TEACHER_CLAIM_REJECT",
                (schoolId, applicantId, revision) -> {
                }));
        ClaimDecision decided = Objects.requireNonNull(result);
        return new ReviewResult(decided.claimStatus() + " " + decided.claimId());
    }

    /**
     * HTTP 与 CLI 共用。expectedRevision 为空时使用锁内读到的版本，供原 CLI 保持原参数。
     * actor 为 cli 时不冒充 App 超管。
     */
    public ClaimDecision reviewAs(String actorId, String claimId, Integer expectedRevision, String decision,
                                  String reason, String auditAction, ClaimRecheck afterLock) {
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "decision 只支持 APPROVE/REJECT");
        }
        String note = reason == null ? "" : reason.strip();
        if (note.isEmpty() || note.length() > 500) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "认领说明需为1至500字");
        }
        TeacherClaimRow located = teachingRepository.findClaim(claimId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        SquareRow square = squareRepository.findByIdForUpdate(located.squareId())
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        TeacherClaimRow claim = teachingRepository.findClaimForUpdate(claimId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        if (!"PENDING".equals(claim.status())) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        int revision = claim.revision();
        if (expectedRevision != null && expectedRevision != revision) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        if (actorId != null && actorId.equals(claim.userId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "不能审核自己的认领申请");
        }
        if (afterLock != null) {
            afterLock.check(square.schoolId(), claim.userId(), revision);
        }
        if ("REJECT".equals(decision)) {
            if (teachingRepository.decideClaim(claimId, "REJECTED", revision) != 1) {
                throw new ApiException(ApiCode.REVISION_CONFLICT);
            }
            String auditId = auditService.recordReturningId(actorId, auditAction, "TEACHER_CLAIM", claimId,
                    revision, revision + 1, note);
            return new ClaimDecision(claimId, "REJECTED", revision + 1, auditId);
        }

        UserRow user = userRepository.findById(claim.userId())
                .orElseThrow(() -> new ApiException(ApiCode.INVALID_STATE_TRANSITION, "认领用户不存在"));
        if (user.identityStatus() != IdentityStatus.VERIFIED
                || user.disabled()
                || !"ACTIVE".equals(user.accountStatus())
                || !userRepository.findRoles(user.id()).contains(Role.TEACHER)) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "认领用户不是可登录的已核验教师");
        }
        if (square.verifiedCourseId() != null) {
            VerifiedCourseRow verified = verifiedCourseRepository.findById(square.verifiedCourseId())
                    .orElseThrow(() -> new ApiException(ApiCode.INVALID_STATE_TRANSITION, "真实教学班不存在"));
            if (!verified.teacherUserId().equals(claim.userId())) {
                throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "该广场的核验教师不是认领人");
            }
        }
        if (teachingRepository.activeAssignment(square.id()).isPresent()) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "课程已有当前负责教师，不能通过认领再授予");
        }
        teachingRepository.ensureActive(square.id(), claim.userId());
        squareRepository.bumpRevisionAndEpoch(square.id());
        if (teachingRepository.decideClaim(claimId, "APPROVED", revision) != 1) {
            throw new ApiException(ApiCode.REVISION_CONFLICT);
        }
        String auditId = auditService.recordReturningId(actorId, auditAction, "TEACHER_CLAIM", claimId,
                revision, revision + 1, note);
        return new ClaimDecision(claimId, "APPROVED", revision + 1, auditId);
    }

    @FunctionalInterface
    public interface ClaimRecheck {
        void check(String schoolId, String applicantId, int revision);
    }

    public record ClaimDecision(String claimId, String claimStatus, int revision, String auditId) {
    }

    public record ReviewResult(String summary) {
    }
}
