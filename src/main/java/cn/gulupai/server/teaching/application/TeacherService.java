package cn.gulupai.server.teaching.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.api.SourceReferenceView;
import cn.gulupai.server.course.api.SquareView;
import cn.gulupai.server.course.application.CourseProjectionService;
import cn.gulupai.server.course.persistence.CourseDictionaryRepository;
import cn.gulupai.server.course.persistence.SchoolRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.MembershipRow;
import cn.gulupai.server.course.persistence.TermRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.course.persistence.VerifiedCourseRow;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.teaching.api.TeachingRequests;
import cn.gulupai.server.teaching.api.TeachingViews;
import cn.gulupai.server.teaching.domain.TeacherCapabilities;
import cn.gulupai.server.teaching.persistence.ClassGrantRepository;
import cn.gulupai.server.teaching.persistence.ClassGrantRow;
import cn.gulupai.server.teaching.persistence.CourseCodeRepository;
import cn.gulupai.server.teaching.persistence.TeacherAssignmentRow;
import cn.gulupai.server.teaching.persistence.TeacherClaimRow;
import cn.gulupai.server.teaching.persistence.TeacherTransferRow;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TeacherService {

    private static final TypeReference<List<SourceReferenceView>> REFERENCE_LIST =
            new TypeReference<>() {
            };

    private final TeachingRepository teachingRepository;
    private final ClassGrantRepository grantRepository;
    private final CourseCodeRepository codeRepository;
    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final CourseDictionaryRepository dictionaryRepository;
    private final UserRepository userRepository;
    private final CourseAuthorityService authorityService;
    private final CourseProjectionService projectionService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TeacherService(TeachingRepository teachingRepository,
                          ClassGrantRepository grantRepository,
                          CourseCodeRepository codeRepository,
                          SquareRepository squareRepository,
                          VerifiedCourseRepository verifiedCourseRepository,
                          CourseDictionaryRepository dictionaryRepository,
                          UserRepository userRepository,
                          CourseAuthorityService authorityService,
                          CourseProjectionService projectionService,
                          CurrentUserPolicy policy,
                          IdempotencyService idempotencyService,
                          AuditService auditService,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.teachingRepository = teachingRepository;
        this.grantRepository = grantRepository;
        this.codeRepository = codeRepository;
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.userRepository = userRepository;
        this.authorityService = authorityService;
        this.projectionService = projectionService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SquareView> adminWorkspaces(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        List<SquareView> items = new ArrayList<>();
        for (ClassGrantRow grant : grantRepository.listCurrentlyActiveByUser(
                principal.userId(), clock.instant())) {
            SquareRow square = squareRepository.findById(grant.squareId()).orElse(null);
            if (square == null) {
                continue;
            }
            items.add(projectionService.square(square, membershipStatus(square.id(), principal.userId()),
                    authorityService.capabilitiesFor(square.id(), principal.userId())));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public TeachingViews.TeachingWorkspacesView teachingWorkspaces(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        Map<String, String> claimStatuses = new LinkedHashMap<>();
        for (TeacherAssignmentRow assignment : teachingRepository.activeAssignmentsForUser(principal.userId())) {
            claimStatuses.put(assignment.squareId(), "ACTIVE");
        }
        for (TeacherClaimRow claim : teachingRepository.pendingClaimsForUser(principal.userId())) {
            claimStatuses.putIfAbsent(claim.squareId(), "PENDING");
        }
        List<TeachingViews.TeachingWorkspaceView> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : claimStatuses.entrySet()) {
            SquareRow square = squareRepository.findById(entry.getKey()).orElse(null);
            if (square == null) {
                continue;
            }
            items.add(new TeachingViews.TeachingWorkspaceView(
                    square.id(), square.id(), square.squareNo(), square.schoolId(), square.termId(),
                    square.name(), square.teacherName(),
                    projectionServiceSquareSlots(square), square.status(), square.revision(),
                    authorityService.capabilitiesFor(square.id(), principal.userId()),
                    membershipStatus(square.id(), principal.userId()), entry.getValue()));
        }
        List<TeachingViews.TeacherTransferView> transfers = teachingRepository
                .pendingTransfersForRecipient(principal.userId()).stream()
                .map(this::transferView).toList();
        return new TeachingViews.TeachingWorkspacesView(items, null, false, items.size(), transfers);
    }

    @Transactional(readOnly = true)
    public List<TeachingViews.GrantView> grants(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        if (authorityService.isTeacher(square.id(), principal.userId())) {
            return grantRepository.listBySquare(square.id()).stream().map(this::grantView).toList();
        }
        ClassGrantRow own = grantRepository.find(square.id(), principal.userId()).orElse(null);
        if (own == null || !authorityService.grantActive(own)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return List.of(grantView(own));
    }

    public TeachingViews.GrantView grant(AuthenticatedUser principal, String squareId,
                                         TeachingRequests.GrantRequest request,
                                         String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        authorityService.requireTeacher(square, principal.userId());
        UserRow target = requireSchoolUser(square.schoolId(), request.studentId());
        requireTargetMember(square, target);
        List<String> capabilities = authorityService.normalizeGrantCapabilities(request.capabilities());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.GrantView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    SquareRow current = requireSquare(principal, squareId);
                    authorityService.requireTeacher(current, principal.userId());
                    requireTargetMember(current, target);
                },
                () -> grantOnce(principal, square, target, capabilities));
    }

    private TeachingViews.GrantView grantOnce(AuthenticatedUser principal, SquareRow square,
                                              UserRow target, List<String> capabilities) {
        SquareRow locked = squareRepository.findByIdForUpdate(square.id())
                .orElseThrow(ApiException::notFound);
        requireTargetMember(locked, target);
        Instant expiresAt = termEnd(locked);
        ClassGrantRow grant = grantRepository.upsert(locked.id(), target.id(),
                writeJson(capabilities), expiresAt, principal.userId());
        squareRepository.bumpRevisionAndEpoch(locked.id());
        auditService.record(principal.userId(), "CLASS_GRANT_CREATE", "CLASS_GRANT", grant.id(),
                null, grant.revision(), null);
        return grantView(grant);
    }

    public TeachingViews.GrantView revokeGrant(AuthenticatedUser principal, String grantId,
                                               TeachingRequests.GrantRevocationRequest request,
                                               String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        ClassGrantRow grant = grantRepository.findById(grantId)
                .orElseThrow(ApiException::notFound);
        SquareRow square = requireSquare(principal, grant.squareId());
        authorityService.requireTeacher(square, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.GrantView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    authorityService.requireTeacher(requireSquare(principal, square.id()), principal.userId());
                },
                () -> revokeGrantOnce(principal, grantId, request));
    }

    private TeachingViews.GrantView revokeGrantOnce(AuthenticatedUser principal, String grantId,
                                                    TeachingRequests.GrantRevocationRequest request) {
        ClassGrantRow grant = grantRepository.findById(grantId)
                .orElseThrow(ApiException::notFound);
        SquareRow locked = squareRepository.findByIdForUpdate(grant.squareId())
                .orElseThrow(ApiException::notFound);
        if (grant.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        int updated = grantRepository.revoke(grantId, request.expectedRevision());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        squareRepository.bumpRevisionAndEpoch(locked.id());
        auditService.record(principal.userId(), "CLASS_GRANT_REVOKE", "CLASS_GRANT", grantId,
                grant.revision(), grant.revision() + 1, request.reason());
        return grantView(grantRepository.findById(grantId).orElseThrow());
    }

    public TeachingViews.TeacherClaimView claim(AuthenticatedUser principal, String squareId,
                                                TeachingRequests.TeacherClaimRequest request,
                                                String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.TEACHER);
        SquareRow square = requireSquare(principal, squareId);
        if (teachingRepository.activeAssignment(square.id()).isPresent()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "当前课程已有负责教师");
        }
        if (square.verifiedCourseId() != null) {
            VerifiedCourseRow verified = verifiedCourseRepository.findById(square.verifiedCourseId())
                    .orElseThrow(ApiException::notFound);
            if (!verified.teacherUserId().equals(principal.userId())) {
                throw new ApiException(ApiCode.FORBIDDEN, "只有核验名单指定的教师可以认领该课程");
            }
        }
        List<SourceReferenceView> references = validateReferences(request.proofRefs());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.TeacherClaimView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    policy.requireRole(principal, Role.TEACHER);
                },
                () -> claimOnce(principal, square, request.message().strip(), references));
    }

    private TeachingViews.TeacherClaimView claimOnce(AuthenticatedUser principal, SquareRow square,
                                                     String message, List<SourceReferenceView> references) {
        TeacherClaimRow existing = teachingRepository.pendingClaim(square.id(), principal.userId())
                .orElse(null);
        if (existing != null) {
            return claimView(existing);
        }
        String id = java.util.UUID.randomUUID().toString();
        teachingRepository.insertClaim(id, square.id(), principal.userId(), message,
                projectionService.referencesJson(references));
        auditService.record(principal.userId(), "TEACHER_CLAIM_SUBMIT", "TEACHER_CLAIM", id,
                null, 1, null);
        return claimView(teachingRepository.findClaim(id).orElseThrow());
    }

    public TeachingViews.RelinquishmentView relinquish(AuthenticatedUser principal, String squareId,
                                                      TeachingRequests.RelinquishRequest request,
                                                      String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        authorityService.requireTeacher(square, principal.userId());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.RelinquishmentView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    authorityService.requireTeacher(requireSquare(principal, squareId), principal.userId());
                },
                () -> relinquishOnce(principal, squareId, request));
    }

    private TeachingViews.RelinquishmentView relinquishOnce(AuthenticatedUser principal, String squareId,
                                                            TeachingRequests.RelinquishRequest request) {
        SquareRow square = squareRepository.findByIdForUpdate(squareId)
                .orElseThrow(ApiException::notFound);
        if (square.revision() != request.expectedSquareRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (grantRepository.countActive(squareId, clock.instant()) > 0) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "请先撤销有效班级授权");
        }
        if (teachingRepository.pendingTransfer(squareId).isPresent()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "存在待回应的课程转交");
        }
        int ended = teachingRepository.endActive(squareId, clock.instant());
        if (ended != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        squareRepository.bumpRevisionAndEpoch(squareId);
        auditService.record(principal.userId(), "TEACHER_RELINQUISH", "SQUARE", squareId,
                square.revision(), square.revision() + 1, request.reason());
        return new TeachingViews.RelinquishmentView(squareId, principal.userId(),
                "RELINQUISHED", square.revision() + 1);
    }

    public TeachingViews.TeacherTransferView createTransfer(AuthenticatedUser principal, String squareId,
                                                            TeachingRequests.TeacherTransferRequest request,
                                                            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        authorityService.requireTeacher(square, principal.userId());
        UserRow recipient = requireSchoolUser(square.schoolId(), request.recipientTeacherId());
        requireVerifiedTeacher(recipient);
        if (recipient.id().equals(principal.userId())) {
            throw ApiException.validation("不能转交给本人");
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.TeacherTransferView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    authorityService.requireTeacher(requireSquare(principal, squareId), principal.userId());
                },
                () -> createTransferOnce(principal, square, recipient, request));
    }

    private TeachingViews.TeacherTransferView createTransferOnce(AuthenticatedUser principal, SquareRow square,
                                                                UserRow recipient,
                                                                TeachingRequests.TeacherTransferRequest request) {
        SquareRow locked = squareRepository.findByIdForUpdate(square.id())
                .orElseThrow(ApiException::notFound);
        if (teachingRepository.pendingTransfer(locked.id()).isPresent()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "已有待回应的课程转交");
        }
        if (locked.revision() != request.expectedSquareRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        String id = java.util.UUID.randomUUID().toString();
        teachingRepository.insertTransfer(id, locked.id(), principal.userId(), recipient.id(),
                locked.revision(), request.reason());
        auditService.record(principal.userId(), "TEACHER_TRANSFER_CREATE", "TEACHER_TRANSFER", id,
                null, 1, request.reason());
        return transferView(teachingRepository.findTransfer(id).orElseThrow());
    }

    public TeachingViews.TeacherTransferView respondTransfer(AuthenticatedUser principal, String transferId,
                                                             TeachingRequests.TransferResponseRequest request,
                                                             String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        TeacherTransferRow transfer = teachingRepository.findTransfer(transferId)
                .orElseThrow(ApiException::notFound);
        if (!transfer.toUserId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if ("DECLINE".equals(request.decision())) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw ApiException.validation("拒绝转交必须说明理由");
            }
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TeachingViews.TeacherTransferView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    TeacherTransferRow current = teachingRepository.findTransfer(transferId)
                            .orElseThrow(ApiException::notFound);
                    if (!current.toUserId().equals(principal.userId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                },
                () -> respondTransferOnce(principal, transferId, request));
    }

    private TeachingViews.TeacherTransferView respondTransferOnce(AuthenticatedUser principal, String transferId,
                                                                  TeachingRequests.TransferResponseRequest request) {
        TeacherTransferRow transfer = teachingRepository.findTransfer(transferId)
                .orElseThrow(ApiException::notFound);
        if (transfer.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!"PENDING".equals(transfer.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("ACCEPT".equals(request.decision())) {
            SquareRow square = squareRepository.findByIdForUpdate(transfer.squareId())
                    .orElseThrow(ApiException::notFound);
            if (square.revision() != transfer.sourceRevision()
                    || !authorityService.isTeacher(square.id(), transfer.fromUserId())) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            teachingRepository.endActive(square.id(), clock.instant());
            teachingRepository.ensureActive(square.id(), transfer.toUserId());
            codeRepository.revokeActive(square.id());
            squareRepository.bumpRevisionAndEpoch(square.id());
            if (teachingRepository.decideTransfer(transferId, request.expectedRevision(), "ACCEPTED") != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            auditService.record(principal.userId(), "TEACHER_TRANSFER_ACCEPT", "TEACHER_TRANSFER", transferId,
                    transfer.revision(), transfer.revision() + 1, null);
        } else {
            if (teachingRepository.decideTransfer(transferId, request.expectedRevision(), "DECLINED") != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            auditService.record(principal.userId(), "TEACHER_TRANSFER_DECLINE", "TEACHER_TRANSFER", transferId,
                    transfer.revision(), transfer.revision() + 1, request.reason());
        }
        return transferView(teachingRepository.findTransfer(transferId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<TeachingViews.TeachingProgressView> teachingProgress(AuthenticatedUser principal,
                                                                    String squareId) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        if (!authorityService.isTeacher(square.id(), principal.userId())
                && !authorityService.hasCapability(square.id(), principal.userId(),
                TeacherCapabilities.TEAM_SUMMARY_READ)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        // 项目/任务表属于 P03；P02B 返回真实空集，不编造项目进展。
        return List.of();
    }

    public SquareRow requireSquare(AuthenticatedUser principal, String squareId) {
        SquareRow square = squareRepository.findById(squareId)
                .orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return square;
    }

    private UserRow requireSchoolUser(String schoolId, String userId) {
        UserRow user = userRepository.findById(userId)
                .orElseThrow(ApiException::notFound);
        if (!user.schoolId().equals(schoolId)) {
            throw ApiException.notFound();
        }
        return user;
    }

    private void requireVerifiedTeacher(UserRow user) {
        if (user.identityStatus() != cn.gulupai.server.identity.domain.IdentityStatus.VERIFIED
                || !userRepository.findRoles(user.id()).contains(Role.TEACHER)) {
            throw ApiException.validation("接任教师必须为已核验TEACHER");
        }
    }

    private void requireTargetMember(SquareRow square, UserRow target) {
        boolean enrolled = square.verifiedCourseId() != null
                && verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), target.id());
        boolean member = squareRepository.findMembership(square.id(), target.id())
                .map(row -> "ACTIVE".equals(row.status())).orElse(false);
        if (!enrolled && !member) {
            throw ApiException.validation("被授权人不是本课程有效成员");
        }
    }

    private Instant termEnd(SquareRow square) {
        TermRow term = dictionaryRepository.findTerm(square.termId())
                .orElseThrow(ApiException::notFound);
        SchoolRow school = dictionaryRepository.findSchool(square.schoolId())
                .orElseThrow(ApiException::notFound);
        return term.endDate().atTime(LocalTime.of(23, 59, 59))
                .atZone(ZoneId.of(school.timezone())).toInstant();
    }

    private List<SourceReferenceView> validateReferences(
            List<TeachingRequests.ProofReferenceRequest> references) {
        if (references == null) {
            return List.of();
        }
        if (references.size() > 3) {
            throw ApiException.validation("proofRefs 最多3项");
        }
        List<SourceReferenceView> result = new ArrayList<>();
        for (TeachingRequests.ProofReferenceRequest reference : references) {
            if (reference == null || !"LINK".equals(reference.kind())) {
                throw ApiException.validation("当前阶段核验材料只支持LINK");
            }
            String url = reference.url().strip();
            if (url.length() > 2048) {
                throw ApiException.validation("核验链接不能超过2048个字符");
            }
            try {
                URI uri = URI.create(url);
                if (uri.getScheme() == null
                        || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                        || uri.getHost() == null) {
                    throw ApiException.validation("核验链接必须为http/https URL");
                }
            } catch (IllegalArgumentException ex) {
                throw ApiException.validation("核验链接格式无效");
            }
            String title = reference.title().strip();
            if (title.isEmpty() || title.length() > 120) {
                throw ApiException.validation("核验材料标题必须为1～120个字符");
            }
            result.add(new SourceReferenceView("LINK", url, title));
        }
        return List.copyOf(result);
    }

    private TeachingViews.GrantView grantView(ClassGrantRow grant) {
        UserRow user = userRepository.findById(grant.userId()).orElse(null);
        List<String> capabilities = authorityService.parseCapabilities(grant.capabilitiesJson());
        boolean active = "ACTIVE".equals(grant.status())
                && grant.expiresAt().isAfter(clock.instant());
        return new TeachingViews.GrantView(
                grant.id(), grant.squareId(), grant.userId(),
                user == null ? "" : user.displayName(), capabilities, grant.status(),
                grant.grantedBy(), grant.expiresAt(), grant.revision(), active);
    }

    private TeachingViews.TeacherClaimView claimView(TeacherClaimRow claim) {
        List<SourceReferenceView> refs;
        try {
            refs = objectMapper.readValue(claim.proofRefsJson(), REFERENCE_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("teacher_claim.proof_refs_json 无法解析", ex);
        }
        return new TeachingViews.TeacherClaimView(claim.id(), claim.squareId(), claim.userId(),
                claim.status(), claim.message(), refs, claim.revision(), claim.createdAt());
    }

    private TeachingViews.TeacherTransferView transferView(TeacherTransferRow transfer) {
        return new TeachingViews.TeacherTransferView(transfer.id(), transfer.squareId(),
                transfer.fromUserId(), transfer.toUserId(), transfer.reason(), transfer.status(),
                transfer.sourceRevision(), transfer.revision(), transfer.createdAt());
    }

    private String membershipStatus(String squareId, String userId) {
        return squareRepository.findMembership(squareId, userId)
                .map(MembershipRow::status).orElse("NONE");
    }

    private List<cn.gulupai.server.course.domain.CourseSlot> projectionServiceSquareSlots(SquareRow square) {
        try {
            return objectMapper.readValue(square.slotsJson(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("square.slots_json 无法解析", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化教师数据", ex);
        }
    }
}
