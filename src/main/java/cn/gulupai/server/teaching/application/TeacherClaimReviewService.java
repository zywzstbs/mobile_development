package cn.gulupai.server.teaching.application;

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
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new IllegalArgumentException("decision 只支持 APPROVE/REJECT");
        }
        if ("REJECT".equals(decision) && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("拒绝认领必须说明理由");
        }
        String result = transactionTemplate.execute(status -> reviewInTransaction(claimId, decision, reason));
        return new ReviewResult(Objects.requireNonNull(result));
    }

    private String reviewInTransaction(String claimId, String decision, String reason) {
        TeacherClaimRow claim = teachingRepository.findClaim(claimId)
                .orElseThrow(() -> new IllegalArgumentException("认领申请不存在: " + claimId));
        if (!"PENDING".equals(claim.status())) {
            throw new IllegalArgumentException("认领申请不是PENDING: " + claim.status());
        }
        SquareRow square = squareRepository.findByIdForUpdate(claim.squareId())
                .orElseThrow(() -> new IllegalArgumentException("课程广场不存在: " + claim.squareId()));
        if ("REJECT".equals(decision)) {
            if (teachingRepository.decideClaim(claimId, "REJECTED") != 1) {
                throw new IllegalArgumentException("认领申请状态已变化");
            }
            auditService.record("cli", "TEACHER_CLAIM_REJECT", "TEACHER_CLAIM", claimId,
                    claim.revision(), claim.revision() + 1, reason);
            return "REJECTED " + claimId;
        }

        UserRow user = userRepository.findById(claim.userId())
                .orElseThrow(() -> new IllegalArgumentException("认领用户不存在"));
        if (user.identityStatus() != IdentityStatus.VERIFIED
                || !userRepository.findRoles(user.id()).contains(Role.TEACHER)) {
            throw new IllegalArgumentException("认领用户不是已核验TEACHER");
        }
        if (square.verifiedCourseId() != null) {
            VerifiedCourseRow verified = verifiedCourseRepository.findById(square.verifiedCourseId())
                    .orElseThrow(() -> new IllegalArgumentException("真实教学班不存在"));
            if (!verified.teacherUserId().equals(claim.userId())) {
                throw new IllegalArgumentException("该广场的核验教师不是认领人");
            }
        }
        if (teachingRepository.activeAssignment(square.id()).isPresent()) {
            throw new IllegalArgumentException("课程已有当前负责教师，不能通过认领再授予");
        }
        teachingRepository.ensureActive(square.id(), claim.userId());
        squareRepository.bumpRevisionAndEpoch(square.id());
        if (teachingRepository.decideClaim(claimId, "APPROVED") != 1) {
            throw new IllegalArgumentException("认领申请状态已变化");
        }
        auditService.record("cli", "TEACHER_CLAIM_APPROVE", "TEACHER_CLAIM", claimId,
                claim.revision(), claim.revision() + 1, reason);
        return "APPROVED " + claimId;
    }

    public record ReviewResult(String summary) {
    }
}
