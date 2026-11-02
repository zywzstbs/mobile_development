package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.pagination.CursorService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.api.CourseMembershipRequests;
import cn.gulupai.server.course.api.CourseMembershipViews;
import cn.gulupai.server.course.api.SourceReferenceView;
import cn.gulupai.server.teaching.persistence.JoinRequestRepository;
import cn.gulupai.server.teaching.persistence.JoinRequestRow;
import cn.gulupai.server.course.persistence.MembershipRow;
import cn.gulupai.server.course.persistence.PreviewTokenRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.identity.domain.IdentityStatus;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
import cn.gulupai.server.teaching.domain.CourseCodeStatus;
import cn.gulupai.server.teaching.domain.JoinRequestStatus;
import cn.gulupai.server.teaching.domain.TeacherCapabilities;
import cn.gulupai.server.teaching.persistence.CourseCodeRepository;
import cn.gulupai.server.teaching.persistence.CourseCodeRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MembershipService {

    private static final String CONFIRM_TOKEN_KIND = "COURSE_JOIN_CONFIRM";
    private static final TypeReference<List<SourceReferenceView>> REFERENCE_LIST =
            new TypeReference<>() {
            };

    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final CourseCodeRepository codeRepository;
    private final CourseAuthorityService authorityService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final CursorService cursorService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MembershipService(SquareRepository squareRepository,
                             VerifiedCourseRepository verifiedCourseRepository,
                             JoinRequestRepository joinRequestRepository,
                             CourseCodeRepository codeRepository,
                             CourseAuthorityService authorityService,
                             CurrentUserPolicy policy,
                             IdempotencyService idempotencyService,
                             CursorService cursorService,
                             AuditService auditService,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.joinRequestRepository = joinRequestRepository;
        this.codeRepository = codeRepository;
        this.authorityService = authorityService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.cursorService = cursorService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CourseMembershipViews.MembershipConfirmationView confirm(
            AuthenticatedUser principal, String squareId,
            CourseMembershipRequests.MembershipConfirmationRequest request,
            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                CourseMembershipViews.MembershipConfirmationView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> confirmOnce(principal, square, request));
    }

    private CourseMembershipViews.MembershipConfirmationView confirmOnce(
            AuthenticatedUser principal, SquareRow square,
            CourseMembershipRequests.MembershipConfirmationRequest request) {
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw ApiException.validation("confirm 必须为true");
        }
        PreviewTokenRow token = squareRepository
                .findPreviewToken(Hashing.sha256Hex(request.confirmationToken()))
                .orElseThrow(() -> ApiException.validation("确认token无效或已使用"));
        if (!CONFIRM_TOKEN_KIND.equals(token.kind())
                || !token.userId().equals(principal.userId())
                || !token.expiresAt().isAfter(clock.instant())) {
            throw ApiException.validation("确认token无效或已过期");
        }
        Map<String, Object> payload = readPayload(token.payloadJson());
        if (!square.id().equals(String.valueOf(payload.get("squareId")))) {
            throw ApiException.validation("确认token不属于当前课程");
        }
        int enrollmentRevision = intValue(payload.get("enrollmentRevision"));
        if (square.verifiedCourseId() == null
                || verifiedCourseRepository.enrollmentRevision(square.verifiedCourseId(), principal.userId())
                .filter(revision -> revision == enrollmentRevision).isEmpty()) {
            throw ApiException.validation("可信选课事实已变化，请重新解析课程码");
        }
        String codeId = String.valueOf(payload.get("codeId"));
        int codeRevision = intValue(payload.get("codeRevision"));
        CourseCodeRow code = codeRepository.findById(codeId)
                .orElseThrow(() -> new ApiException(ApiCode.CODE_INVALID));
        if (!CourseCodeStatus.ACTIVE.name().equals(code.status())
                || code.revision() != codeRevision
                || !code.expiresAt().isAfter(clock.instant())) {
            throw ApiException.validation("课程码已失效，请重新解析");
        }

        SquareRow locked = squareRepository.findByIdForUpdate(square.id())
                .orElseThrow(ApiException::notFound);
        MembershipRow membership = squareRepository.findMembership(locked.id(), principal.userId())
                .orElse(null);
        if (membership == null || !"ACTIVE".equals(membership.status())) {
            squareRepository.ensureMembership(locked.id(), principal.userId());
            membership = squareRepository.findMembership(locked.id(), principal.userId()).orElseThrow();
            JoinRequestRow pending = joinRequestRepository.findPending(locked.id(), principal.userId())
                    .orElse(null);
            if (pending != null) {
                joinRequestRepository.decide(pending.id(), pending.revision(), "APPROVED",
                        principal.userId(), "可信名单确认入课");
            }
            squareRepository.bumpEpoch(locked.id());
            auditService.record(principal.userId(), "COURSE_MEMBERSHIP_CONFIRM", "COURSE_MEMBERSHIP",
                    membership.id(), null, membership.revision(), null);
        }
        squareRepository.deletePreviewToken(Hashing.sha256Hex(request.confirmationToken()));
        return new CourseMembershipViews.MembershipConfirmationView(locked.id(), principal.userId(),
                membership.status(), membership.revision());
    }

    public CourseMembershipViews.JoinRequestView createJoinRequest(
            AuthenticatedUser principal, String squareId,
            CourseMembershipRequests.JoinRequestCreateRequest request,
            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        policy.requireRole(principal, Role.STUDENT);
        SquareRow square = requireSquare(principal, squareId);
        if (principal.identityStatus() == null
                || !IdentityStatus.VERIFIED.name().equals(principal.identityStatus())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (membershipActive(square.id(), principal.userId())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "本人已是课程成员");
        }
        JoinRequestRow existing = joinRequestRepository.findPending(square.id(), principal.userId())
                .orElse(null);
        if (existing != null) {
            return requestView(existing);
        }
        if (!authorityService.hasReviewerOtherThan(square.id(), principal.userId())) {
            throw new ApiException(ApiCode.COURSE_REVIEW_UNAVAILABLE);
        }
        String message = request.message() == null ? "" : request.message().strip();
        if (message.length() > 500) {
            throw ApiException.validation("message 不能超过500个字符");
        }
        List<SourceReferenceView> references = validateReferences(request.proofRefs());
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                CourseMembershipViews.JoinRequestView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> createJoinRequestOnce(principal, squareId, message, references));
    }

    private CourseMembershipViews.JoinRequestView createJoinRequestOnce(
            AuthenticatedUser principal, String squareId, String message,
            List<SourceReferenceView> references) {
        SquareRow square = requireSquare(principal, squareId);
        JoinRequestRow existing = joinRequestRepository.findPending(squareId, principal.userId())
                .orElse(null);
        if (existing != null) {
            return requestView(existing);
        }
        if (!authorityService.hasReviewerOtherThan(square.id(), principal.userId())) {
            throw new ApiException(ApiCode.COURSE_REVIEW_UNAVAILABLE);
        }
        String id = UUID.randomUUID().toString();
        joinRequestRepository.insert(id, squareId, principal.userId(), message,
                objectMapperWrite(references));
        auditService.record(principal.userId(), "COURSE_JOIN_REQUEST", "COURSE_JOIN_REQUEST", id,
                null, 1, null);
        return requestView(joinRequestRepository.findById(id).orElseThrow());
    }

    @Transactional
    public ListEnvelope<CourseMembershipViews.JoinRequestView> myRequests(
            AuthenticatedUser principal, String status, String cursor, Integer limit) {
        policy.requireActiveBusinessUser(principal);
        String normalizedStatus = normalizeStatus(status);
        int pageSize = normalizeLimit(limit);
        String scope = "ME_JOIN_REQUESTS";
        String queryHash = Hashing.sha256Hex(String.valueOf(normalizedStatus));
        List<String> ids = joinRequestRepository.listIdsForUser(principal.userId(), normalizedStatus);
        CursorService.CursorData data = cursor == null ? new CursorService.CursorData(ids, 0, ids.size())
                : cursorService.resolve(cursor, principal.userId(), scope, queryHash);
        ids = data.ids();
        int position = data.position();
        int end = Math.min(ids.size(), position + pageSize);
        List<String> pageIds = position >= ids.size() ? List.of() : ids.subList(position, end);
        String next = end < ids.size()
                ? cursorService.create(principal.userId(), scope, queryHash, 0, ids, end) : null;
        List<CourseMembershipViews.JoinRequestView> items = orderedViews(pageIds);
        return ListEnvelope.page(items, next, next != null, ids.size());
    }

    @Transactional
    public ListEnvelope<CourseMembershipViews.JoinRequestView> squareRequests(
            AuthenticatedUser principal, String squareId, String status, String cursor, Integer limit) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = requireSquare(principal, squareId);
        requireReviewer(square, principal.userId());
        String normalizedStatus = normalizeStatus(status);
        int pageSize = normalizeLimit(limit);
        String scope = "SQUARE_JOIN_REQUESTS:" + square.id();
        String queryHash = Hashing.sha256Hex(String.valueOf(normalizedStatus) + "|"
                + square.authorizationEpoch());
        List<String> ids = joinRequestRepository.listIdsForSquare(square.id(), normalizedStatus,
                principal.userId());
        CursorService.CursorData data = cursor == null ? new CursorService.CursorData(ids, 0, ids.size())
                : cursorService.resolve(cursor, principal.userId(), scope, queryHash);
        ids = data.ids();
        int position = data.position();
        int end = Math.min(ids.size(), position + pageSize);
        List<String> pageIds = position >= ids.size() ? List.of() : ids.subList(position, end);
        String next = end < ids.size()
                ? cursorService.create(principal.userId(), scope, queryHash, square.authorizationEpoch(),
                ids, end) : null;
        return ListEnvelope.page(orderedViews(pageIds), next, next != null, ids.size());
    }

    @Transactional(readOnly = true)
    public CourseMembershipViews.JoinRequestView requestDetail(AuthenticatedUser principal, String requestId) {
        policy.requireActiveBusinessUser(principal);
        JoinRequestRow request = joinRequestRepository.findById(requestId)
                .orElseThrow(ApiException::notFound);
        SquareRow square = requireSquare(principal, request.squareId());
        if (!request.userId().equals(principal.userId())) {
            requireReviewer(square, principal.userId());
        }
        return requestView(request);
    }

    public CourseMembershipViews.JoinRequestDecisionView decide(
            AuthenticatedUser principal, String requestId,
            CourseMembershipRequests.JoinRequestDecisionRequest request,
            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        JoinRequestRow current = joinRequestRepository.findById(requestId)
                .orElseThrow(ApiException::notFound);
        SquareRow square = requireSquare(principal, current.squareId());
        requireReviewer(square, principal.userId());
        if (current.userId().equals(principal.userId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN);
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                CourseMembershipViews.JoinRequestDecisionView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    JoinRequestRow latest = joinRequestRepository.findById(requestId)
                            .orElseThrow(ApiException::notFound);
                    requireReviewer(requireSquare(principal, latest.squareId()), principal.userId());
                    if (latest.userId().equals(principal.userId())) {
                        throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN);
                    }
                },
                () -> decideOnce(principal, requestId, request));
    }

    private CourseMembershipViews.JoinRequestDecisionView decideOnce(
            AuthenticatedUser principal, String requestId,
            CourseMembershipRequests.JoinRequestDecisionRequest request) {
        JoinRequestRow current = joinRequestRepository.findById(requestId)
                .orElseThrow(ApiException::notFound);
        SquareRow locked = squareRepository.findByIdForUpdate(current.squareId())
                .orElseThrow(ApiException::notFound);
        if (current.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!"PENDING".equals(current.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (current.userId().equals(principal.userId())) {
            throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN);
        }
        if ("APPROVE".equals(request.decision())) {
            if (locked.verifiedCourseId() == null
                    || !verifiedCourseRepository.isValidEnrollment(locked.verifiedCourseId(), current.userId())) {
                throw ApiException.validation("批准入课必须有已核验的选课事实");
            }
            squareRepository.ensureMembership(locked.id(), current.userId());
            if (joinRequestRepository.decide(requestId, request.expectedRevision(), "APPROVED",
                    principal.userId(), request.reason()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            squareRepository.bumpEpoch(locked.id());
            JoinRequestRow updated = joinRequestRepository.findById(requestId).orElseThrow();
            MembershipRow membership = squareRepository.findMembership(locked.id(), current.userId())
                    .orElseThrow();
            auditService.record(principal.userId(), "COURSE_JOIN_REQUEST_APPROVE",
                    "COURSE_JOIN_REQUEST", requestId, current.revision(), updated.revision(),
                    request.reason());
            return new CourseMembershipViews.JoinRequestDecisionView(requestView(updated),
                    membershipView(membership));
        }
        if (joinRequestRepository.decide(requestId, request.expectedRevision(), "REJECTED",
                principal.userId(), request.reason()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        JoinRequestRow updated = joinRequestRepository.findById(requestId).orElseThrow();
        auditService.record(principal.userId(), "COURSE_JOIN_REQUEST_REJECT",
                "COURSE_JOIN_REQUEST", requestId, current.revision(), updated.revision(),
                request.reason());
        return new CourseMembershipViews.JoinRequestDecisionView(requestView(updated), null);
    }

    private List<CourseMembershipViews.JoinRequestView> orderedViews(List<String> ids) {
        Map<String, JoinRequestRow> byId = new LinkedHashMap<>();
        joinRequestRepository.listByIds(ids).forEach(row -> byId.put(row.id(), row));
        List<CourseMembershipViews.JoinRequestView> result = new ArrayList<>();
        for (String id : ids) {
            JoinRequestRow row = byId.get(id);
            if (row != null) {
                result.add(requestView(row));
            }
        }
        return result;
    }

    private CourseMembershipViews.JoinRequestView requestView(JoinRequestRow row) {
        List<SourceReferenceView> refs;
        try {
            refs = objectMapper.readValue(row.proofRefsJson(), REFERENCE_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("course_join_request.proof_refs_json 无法解析", ex);
        }
        return new CourseMembershipViews.JoinRequestView(row.id(), row.squareId(), row.userId(),
                row.status(), row.message(), refs, row.decisionReason(), row.revision(), row.createdAt());
    }

    private CourseMembershipViews.MembershipView membershipView(MembershipRow row) {
        return new CourseMembershipViews.MembershipView(row.squareId(), row.userId(),
                row.status(), row.revision());
    }

    private void requireReviewer(SquareRow square, String userId) {
        if (!authorityService.isTeacher(square.id(), userId)
                && !authorityService.hasCapability(square.id(), userId,
                TeacherCapabilities.COURSE_MEMBERSHIP_REVIEW)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private SquareRow requireSquare(AuthenticatedUser principal, String squareId) {
        SquareRow square = squareRepository.findById(squareId).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return square;
    }

    private boolean membershipActive(String squareId, String userId) {
        return squareRepository.findMembership(squareId, userId)
                .map(row -> "ACTIVE".equals(row.status())).orElse(false);
    }

    private List<SourceReferenceView> validateReferences(
            List<CourseMembershipRequests.ProofReferenceRequest> references) {
        if (references == null) {
            return List.of();
        }
        if (references.size() > 3) {
            throw ApiException.validation("proofRefs 最多3项");
        }
        List<SourceReferenceView> result = new ArrayList<>();
        for (CourseMembershipRequests.ProofReferenceRequest reference : references) {
            if (reference == null || !"LINK".equals(reference.kind())) {
                throw ApiException.validation("当前阶段核验材料只支持LINK");
            }
            String url = reference.url().strip();
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
            result.add(new SourceReferenceView("LINK", url, reference.title().strip()));
        }
        return List.copyOf(result);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!List.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw ApiException.validation("status 取值无效");
        }
        return status;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 100) {
            throw ApiException.validation("limit 必须在1～100之间");
        }
        return limit;
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("preview_token.payload_json 无法解析", ex);
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String objectMapperWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化入课申请", ex);
        }
    }
}
