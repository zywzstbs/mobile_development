package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.api.CourseMembershipRequests;
import cn.gulupai.server.course.api.CourseMembershipViews;
import cn.gulupai.server.course.domain.CourseSlot;
import cn.gulupai.server.teaching.persistence.JoinRequestRepository;
import cn.gulupai.server.course.persistence.MembershipRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
import cn.gulupai.server.teaching.domain.CourseCodeStatus;
import cn.gulupai.server.teaching.domain.TeacherCapabilities;
import cn.gulupai.server.teaching.persistence.CourseCodeRepository;
import cn.gulupai.server.teaching.persistence.CourseCodeRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CourseCodeService {

    private static final String CONFIRM_TOKEN_KIND = "COURSE_JOIN_CONFIRM";
    private static final Duration CONFIRM_TOKEN_TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<CourseSlot>> SLOT_LIST = new TypeReference<>() {
    };

    private final CourseCodeRepository codeRepository;
    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final CourseAuthorityService authorityService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final IdempotencyCipher cipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CourseCodeService(CourseCodeRepository codeRepository,
                             SquareRepository squareRepository,
                             VerifiedCourseRepository verifiedCourseRepository,
                             JoinRequestRepository joinRequestRepository,
                             CourseAuthorityService authorityService,
                             CurrentUserPolicy policy,
                             IdempotencyService idempotencyService,
                             IdempotencyCipher cipher,
                             AuditService auditService,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.codeRepository = codeRepository;
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.joinRequestRepository = joinRequestRepository;
        this.authorityService = authorityService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CourseMembershipViews.CourseCodeView issue(AuthenticatedUser principal, String squareId,
                                                      CourseMembershipRequests.CourseCodeRequest request,
                                                      String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        requireCodeManager(square, principal.userId());
        int expiresIn = request.expiresInSeconds() == null ? 86400 : request.expiresInSeconds();
        if (expiresIn < 300 || expiresIn > 86400) {
            throw ApiException.validation("expiresInSeconds 必须在300～86400之间");
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                CourseMembershipViews.CourseCodeView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireCodeManager(requireSquare(principal, squareId), principal.userId());
                },
                () -> issueOnce(principal, squareId, request.expectedRevision(), expiresIn));
    }

    private CourseMembershipViews.CourseCodeView issueOnce(AuthenticatedUser principal, String squareId,
                                                           int expectedRevision, int expiresIn) {
        SquareRow square = squareRepository.findByIdForUpdate(squareId)
                .orElseThrow(ApiException::notFound);
        if (square.revision() != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        requireCodeManager(square, principal.userId());
        codeRepository.revokeActive(squareId);
        String code = Hashing.newToken();
        Instant expiresAt = clock.instant().plusSeconds(expiresIn);
        int version = codeRepository.nextCodeVersion(squareId);
        codeRepository.insert(squareId, Hashing.sha256Hex(code), cipher.encrypt(code), cipher.keyId(),
                version, expiresAt, principal.userId());
        squareRepository.bumpRevisionAndEpoch(squareId);
        CourseCodeRow created = codeRepository.findActive(squareId, clock.instant()).orElseThrow();
        auditService.record(principal.userId(), "COURSE_CODE_ISSUE", "COURSE_CODE", created.id(),
                null, created.revision(), null);
        return codeView(created, code);
    }

    @Transactional(readOnly = true)
    public CourseMembershipViews.CourseCodeView current(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        requireCodeManager(square, principal.userId());
        CourseCodeRow code = codeRepository.findActive(squareId, clock.instant()).orElse(null);
        return code == null ? null : codeView(code, cipher.decrypt(code.codeCiphertext()));
    }

    public CourseMembershipViews.CourseCodeView revoke(AuthenticatedUser principal, String codeId,
                                                       CourseMembershipRequests.CodeRevocationRequest request,
                                                       String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        CourseCodeRow code = codeRepository.findById(codeId).orElseThrow(ApiException::notFound);
        SquareRow square = requireSquare(principal, code.squareId());
        requireCodeManager(square, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                CourseMembershipViews.CourseCodeView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    requireCodeManager(requireSquare(principal, square.id()), principal.userId());
                },
                () -> revokeOnce(principal, codeId, request));
    }

    private CourseMembershipViews.CourseCodeView revokeOnce(AuthenticatedUser principal, String codeId,
                                                            CourseMembershipRequests.CodeRevocationRequest request) {
        CourseCodeRow code = codeRepository.findById(codeId).orElseThrow(ApiException::notFound);
        SquareRow locked = squareRepository.findByIdForUpdate(code.squareId())
                .orElseThrow(ApiException::notFound);
        if (code.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        int updated = codeRepository.revoke(codeId, request.expectedRevision());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        squareRepository.bumpRevisionAndEpoch(locked.id());
        auditService.record(principal.userId(), "COURSE_CODE_REVOKE", "COURSE_CODE", codeId,
                code.revision(), code.revision() + 1, request.reason());
        return new CourseMembershipViews.CourseCodeView(code.id(), code.squareId(), null,
                code.issuedBy(), code.expiresAt(), "REVOKED", code.revision() + 1);
    }

    @Transactional
    public CourseMembershipViews.CodeResolutionView resolve(AuthenticatedUser principal,
                                                            String rawCode) {
        policy.requireActiveBusinessUser(principal);
        CourseCodeRow code = codeRepository.findByHash(Hashing.sha256Hex(rawCode))
                .orElseThrow(() -> new ApiException(ApiCode.CODE_INVALID));
        if (CourseCodeStatus.REVOKED.name().equals(code.status())) {
            throw new ApiException(ApiCode.CODE_REVOKED);
        }
        if (!CourseCodeStatus.ACTIVE.name().equals(code.status())) {
            throw new ApiException(ApiCode.CODE_DISABLED);
        }
        if (!code.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.CODE_EXPIRED);
        }
        SquareRow square = squareRepository.findById(code.squareId())
                .orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }

        MembershipRow membership = squareRepository.findMembership(square.id(), principal.userId())
                .orElse(null);
        var pending = joinRequestRepository.findPending(square.id(), principal.userId()).orElse(null);
        String membershipStatus;
        if (membership != null && "ACTIVE".equals(membership.status())) {
            membershipStatus = "ACTIVE";
        } else if (pending != null) {
            membershipStatus = "PENDING";
        } else {
            membershipStatus = "NONE";
        }

        boolean verified = square.verifiedCourseId() != null
                && verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), principal.userId());
        String verificationStatus = verified ? "VERIFIED" : "REQUIRES_REVIEW";
        boolean canConfirm = verified && !"ACTIVE".equals(membershipStatus);
        String token = null;
        if (canConfirm) {
            token = issueConfirmationToken(principal, square, code, membership);
        }
        return new CourseMembershipViews.CodeResolutionView(
                joinCodeSquare(square), membershipStatus, verificationStatus, canConfirm,
                pending == null ? null : pending.id(), token);
    }

    private String issueConfirmationToken(AuthenticatedUser principal, SquareRow square,
                                          CourseCodeRow code, MembershipRow membership) {
        int enrollmentRevision = verifiedCourseRepository
                .enrollmentRevision(square.verifiedCourseId(), principal.userId())
                .orElseThrow(() -> ApiException.validation("缺少有效核验选课事实"));
        String token = Hashing.newToken();
        long codeRevision = code.revision();
        Map<String, Object> payload = Map.of(
                "squareId", square.id(),
                "codeId", code.id(),
                "codeRevision", codeRevision,
                "enrollmentRevision", enrollmentRevision,
                "membershipRevision", membership == null ? 0 : membership.revision());
        String bodyHash = Hashing.sha256Hex(square.id() + "|" + code.id() + "|"
                + codeRevision + "|" + enrollmentRevision);
        squareRepository.insertPreviewToken(UUID.randomUUID().toString(), Hashing.sha256Hex(token),
                principal.userId(), CONFIRM_TOKEN_KIND, bodyHash, bodyHash, writeJson(payload),
                clock.instant().plus(CONFIRM_TOKEN_TTL));
        return token;
    }

    public CourseMembershipViews.JoinCodeSquareView joinCodeSquare(SquareRow square) {
        try {
            List<CourseSlot> slots = objectMapper.readValue(square.slotsJson(), SLOT_LIST);
            return new CourseMembershipViews.JoinCodeSquareView(square.id(), square.squareNo(),
                    square.schoolId(), square.termId(), square.name(), square.teacherName(),
                    slots, square.revision());
        } catch (Exception ex) {
            throw new IllegalStateException("square.slots_json 无法解析", ex);
        }
    }

    public SquareRow requireSquare(AuthenticatedUser principal, String squareId) {
        SquareRow square = squareRepository.findById(squareId).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return square;
    }

    private void requireCodeManager(SquareRow square, String userId) {
        if (!authorityService.isTeacher(square.id(), userId)
                && !authorityService.hasCapability(square.id(), userId,
                TeacherCapabilities.COURSE_JOIN_CODE_MANAGE)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private CourseMembershipViews.CourseCodeView codeView(CourseCodeRow code, String rawCode) {
        return new CourseMembershipViews.CourseCodeView(code.id(), code.squareId(), rawCode,
                code.issuedBy(), code.expiresAt(), code.status(), code.revision());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化确认token", ex);
        }
    }
}
