package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.api.AssignmentView;
import cn.gulupai.server.course.api.CourseMatchRequest;
import cn.gulupai.server.course.api.CourseMatchView;
import cn.gulupai.server.course.api.CreateSquareRequest;
import cn.gulupai.server.course.api.NormalizedCourseInputView;
import cn.gulupai.server.course.api.SquareView;
import cn.gulupai.server.course.persistence.CourseDictionaryRepository;
import cn.gulupai.server.course.persistence.PreviewTokenRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.course.persistence.VerifiedCourseRow;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
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
public class SquareService {

    private static final String MATCH_TOKEN_KIND = "COURSE_MATCH";
    private static final Duration MATCH_TOKEN_TTL = Duration.ofMinutes(5);

    private final SquareRepository squareRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final CourseDictionaryRepository dictionaryRepository;
    private final CourseLookupService lookupService;
    private final CourseProjectionService projectionService;
    private final CourseAuthorityService authorityService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SquareService(SquareRepository squareRepository,
                         VerifiedCourseRepository verifiedCourseRepository,
                         CourseDictionaryRepository dictionaryRepository,
                         CourseLookupService lookupService,
                         CourseProjectionService projectionService,
                         CourseAuthorityService authorityService,
                         CurrentUserPolicy policy,
                         IdempotencyService idempotencyService,
                         AuditService auditService,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.squareRepository = squareRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.dictionaryRepository = dictionaryRepository;
        this.lookupService = lookupService;
        this.projectionService = projectionService;
        this.authorityService = authorityService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CourseMatchView match(AuthenticatedUser principal, CourseMatchRequest request) {
        policy.requireActiveBusinessUser(principal);
        NormalizedCourseInput input = lookupService.normalize(principal, request);
        SquareRow exact = squareRepository.findByCanonicalKey(input.canonicalKey()).orElse(null);
        SquareView exactView = exact == null ? null
                : projectionService.square(exact, membershipStatus(exact.id(), principal.userId()), authorityService.capabilitiesFor(exact.id(), principal.userId()));

        Instant now = clock.instant();
        Instant expiresAt = now.plus(MATCH_TOKEN_TTL);
        String token = Hashing.newToken();
        squareRepository.insertPreviewToken(
                UUID.randomUUID().toString(),
                Hashing.sha256Hex(token),
                principal.userId(),
                MATCH_TOKEN_KIND,
                input.canonicalKey(),
                input.canonicalKey(),
                writeJson(input),
                expiresAt);
        return new CourseMatchView(exactView, List.of(),
                new NormalizedCourseInputView(input.schoolId(), input.termId(), input.courseNameId(),
                        input.teacherName(), input.slots()),
                exact == null,
                token,
                expiresAt);
    }

    public SquareView create(AuthenticatedUser principal, CreateSquareRequest request,
                             String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        NormalizedCourseInput input = lookupService.normalize(principal, request);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                SquareView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> createOnce(principal, input, request.candidateCheckToken()));
    }

    private SquareView createOnce(AuthenticatedUser principal, NormalizedCourseInput input,
                                  String candidateCheckToken) {
        requireValidMatchToken(principal, input.canonicalKey(), candidateCheckToken);
        squareRepository.lockCanonical(input.canonicalKey());

        SquareRow existing = squareRepository.findByCanonicalKey(input.canonicalKey()).orElse(null);
        if (existing != null) {
            ensureVerifiedMembership(existing, principal.userId());
            return projectionService.square(existing, membershipStatus(existing.id(), principal.userId()), authorityService.capabilitiesFor(existing.id(), principal.userId()));
        }

        VerifiedCourseRow verified = verifiedCourseRepository
                .findByCanonicalKey(input.canonicalKey()).orElse(null);
        String squareId = UUID.randomUUID().toString();
        String squareNo = squareRepository.nextSquareNo();
        squareRepository.insertSquare(
                squareId,
                verified == null ? null : verified.id(),
                input.schoolId(),
                input.termId(),
                input.courseFullName(),
                input.teacherName(),
                projectionService.slotsJson(input.slots()),
                input.canonicalKey(),
                squareNo);
        auditService.record(principal.userId(), "SQUARE_CREATE", "SQUARE", squareId,
                null, 1, null);

        SquareRow created = squareRepository.findById(squareId)
                .orElseThrow(() -> new IllegalStateException("刚创建的课程广场不可见"));
        if (verified != null) {
            authorityService.ensureTeacher(squareId, verified.teacherUserId());
        }
        ensureVerifiedMembership(created, principal.userId());
        return projectionService.square(created, membershipStatus(created.id(), principal.userId()), authorityService.capabilitiesFor(created.id(), principal.userId()));
    }

    private void requireValidMatchToken(AuthenticatedUser principal, String bodyHash, String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.validation("缺少 candidateCheckToken，请先调用课程匹配");
        }
        PreviewTokenRow row = squareRepository.findPreviewToken(Hashing.sha256Hex(token))
                .orElseThrow(() -> ApiException.validation("课程匹配token无效，请重新匹配"));
        if (!MATCH_TOKEN_KIND.equals(row.kind())
                || !row.userId().equals(principal.userId())
                || !row.bodyHash().equals(bodyHash)
                || !row.expiresAt().isAfter(clock.instant())) {
            throw ApiException.validation("课程匹配已失效或输入已变化，请重新匹配");
        }
    }

    @Transactional(readOnly = true)
    public SquareView get(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        SquareRow row = requireReadableSquare(principal, squareId);
        return projectionService.square(row, membershipStatus(row.id(), principal.userId()), authorityService.capabilitiesFor(row.id(), principal.userId()));
    }

    @Transactional(readOnly = true)
    public List<SquareView> list(AuthenticatedUser principal, String schoolId, String termId, String q) {
        policy.requireActiveBusinessUser(principal);
        lookupService.requireSameSchool(principal, schoolId);
        dictionaryRepository.findSchool(schoolId).orElseThrow(ApiException::notFound);
        lookupService.requireTerm(schoolId, termId);
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) {
            throw ApiException.validation("q 不能超过80个字符");
        }
        List<SquareRow> rows = squareRepository.list(schoolId, termId, query);
        Map<String, String> memberships = squareRepository.membershipStatuses(
                rows.stream().map(SquareRow::id).toList(), principal.userId());
        return rows.stream()
                .map(row -> projectionService.square(row,
                        memberships.getOrDefault(row.id(), "NONE"),
                        authorityService.capabilitiesFor(row.id(), principal.userId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SquareView resolveByNo(AuthenticatedUser principal, String squareNo) {
        policy.requireActiveBusinessUser(principal);
        SquareRow row = squareRepository.findBySquareNo(squareNo)
                .orElseThrow(ApiException::notFound);
        if (!row.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return projectionService.square(row, membershipStatus(row.id(), principal.userId()), authorityService.capabilitiesFor(row.id(), principal.userId()));
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> listAssignments(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        requireReadableSquare(principal, squareId);
        return squareRepository.listAssignments(squareId).stream()
                .map(projectionService::assignment).toList();
    }

    public SquareRow requireReadableSquare(AuthenticatedUser principal, String squareId) {
        SquareRow row = squareRepository.findById(squareId)
                .orElseThrow(ApiException::notFound);
        if (!row.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        return row;
    }

    public String membershipStatus(String squareId, String userId) {
        return squareRepository.membershipStatus(squareId, userId).orElse("NONE");
    }

    public void ensureVerifiedMembership(SquareRow square, String userId) {
        if (square.verifiedCourseId() == null) {
            return;
        }
        boolean eligible = authorityService.isTeacher(square.id(), userId)
                || verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), userId);
        if (eligible) {
            squareRepository.ensureMembership(square.id(), userId);
        }
    }

    public boolean isTeacherOf(SquareRow square, String userId) {
        return authorityService.isTeacher(square.id(), userId);
    }

    public boolean isVerifiedStudentOf(SquareRow square, String userId) {
        return square.verifiedCourseId() != null
                && verifiedCourseRepository.isValidEnrollment(square.verifiedCourseId(), userId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化课程匹配输入", ex);
        }
    }
}
