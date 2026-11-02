package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.api.AssignmentView;
import cn.gulupai.server.course.api.ConfirmAssignmentRequest;
import cn.gulupai.server.course.api.CreateAssignmentRequest;
import cn.gulupai.server.course.api.SourceReferenceRequest;
import cn.gulupai.server.course.api.SourceReferenceView;
import cn.gulupai.server.course.persistence.AssignmentRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssignmentService {

    private final SquareRepository squareRepository;
    private final SquareService squareService;
    private final CourseProjectionService projectionService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final Clock clock;

    public AssignmentService(SquareRepository squareRepository,
                             SquareService squareService,
                             CourseProjectionService projectionService,
                             CurrentUserPolicy policy,
                             IdempotencyService idempotencyService,
                             AuditService auditService,
                             Clock clock) {
        this.squareRepository = squareRepository;
        this.squareService = squareService;
        this.projectionService = projectionService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssignmentView get(AuthenticatedUser principal, String assignmentId) {
        policy.requireActiveBusinessUser(principal);
        AssignmentRow row = squareRepository.findAssignment(assignmentId)
                .orElseThrow(ApiException::notFound);
        squareService.requireReadableSquare(principal, row.squareId());
        return projectionService.assignment(row);
    }

    public AssignmentView create(AuthenticatedUser principal, String squareId,
                                 CreateAssignmentRequest request,
                                 String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = squareService.requireReadableSquare(principal, squareId);
        NormalizedAssignment normalized = normalize(request);
        if (relation(principal, square) == Relation.NONE) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                AssignmentView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    SquareRow current = squareService.requireReadableSquare(principal, squareId);
                    if (relation(principal, current) == Relation.NONE) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                },
                () -> createOnce(principal, squareId, normalized));
    }

    private AssignmentView createOnce(AuthenticatedUser principal, String squareId,
                                      NormalizedAssignment normalized) {
        SquareRow square = squareService.requireReadableSquare(principal, squareId);
        Relation current = relation(principal, square);
        if (current == Relation.NONE) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        String origin = current == Relation.TEACHER ? "TEACHER" : "STUDENT";
        String confirmation = current == Relation.TEACHER ? "CONFIRMED" : "PENDING_CONFIRMATION";
        String id = java.util.UUID.randomUUID().toString();
        squareRepository.insertAssignment(id, squareId, normalized.name(), normalized.requirements(),
                origin, confirmation, normalized.hardDueAt(), normalized.minMembers(),
                normalized.maxMembers(), projectionService.referencesJson(normalized.references()));
        auditService.record(principal.userId(), "ASSIGNMENT_CREATE", "ASSIGNMENT", id,
                null, 1, null);
        AssignmentRow created = squareRepository.findAssignment(id)
                .orElseThrow(() -> new IllegalStateException("刚创建的作业不可见"));
        return projectionService.assignment(created);
    }

    public AssignmentView confirm(AuthenticatedUser principal, String assignmentId,
                                  ConfirmAssignmentRequest request,
                                  String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        AssignmentRow row = squareRepository.findAssignment(assignmentId)
                .orElseThrow(ApiException::notFound);
        SquareRow square = squareService.requireReadableSquare(principal, row.squareId());
        if (!squareService.isTeacherOf(square, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                AssignmentView.class,
                () -> {
                    policy.requireActiveBusinessUser(principal);
                    AssignmentRow current = squareRepository.findAssignment(assignmentId)
                            .orElseThrow(ApiException::notFound);
                    SquareRow currentSquare = squareService.requireReadableSquare(
                            principal, current.squareId());
                    if (!squareService.isTeacherOf(currentSquare, principal.userId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                },
                () -> confirmOnce(principal, assignmentId, request));
    }

    private AssignmentView confirmOnce(AuthenticatedUser principal, String assignmentId,
                                       ConfirmAssignmentRequest request) {
        AssignmentRow row = squareRepository.findAssignment(assignmentId)
                .orElseThrow(ApiException::notFound);
        SquareRow square = squareService.requireReadableSquare(principal, row.squareId());
        if (!squareService.isTeacherOf(square, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (row.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (!"PENDING_CONFIRMATION".equals(row.confirmationStatus())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        String reason = request.reason() == null ? null : request.reason().strip();
        if (reason != null && reason.length() > 500) {
            throw ApiException.validation("reason 不能超过500个字符");
        }
        int updated = squareRepository.confirmAssignment(assignmentId, request.expectedRevision());
        if (updated != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        AssignmentRow confirmed = squareRepository.findAssignment(assignmentId)
                .orElseThrow(() -> new IllegalStateException("刚确认的作业不可见"));
        auditService.record(principal.userId(), "ASSIGNMENT_CONFIRM", "ASSIGNMENT", assignmentId,
                row.revision(), confirmed.revision(), reason);
        return projectionService.assignment(confirmed);
    }

    private Relation relation(AuthenticatedUser principal, SquareRow square) {
        if (squareService.isTeacherOf(square, principal.userId())) {
            return Relation.TEACHER;
        }
        if (squareService.isVerifiedStudentOf(square, principal.userId())) {
            return Relation.STUDENT;
        }
        return Relation.NONE;
    }

    private NormalizedAssignment normalize(CreateAssignmentRequest request) {
        String name = request.name().strip();
        String requirements = request.requirements().strip();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.validation("作业名必须为1～120个字符");
        }
        if (requirements.isEmpty() || requirements.length() > 5000) {
            throw ApiException.validation("作业要求必须为1～5000个字符");
        }
        if (!request.hardDueAt().isAfter(clock.instant())) {
            throw ApiException.validation("hardDueAt 必须晚于服务器当前时间");
        }
        if (request.minMembers() < 1 || request.maxMembers() > 20
                || request.minMembers() > request.maxMembers()) {
            throw ApiException.validation("团队人数范围无效");
        }
        List<SourceReferenceView> references = new ArrayList<>();
        if (request.sourceReferences() != null) {
            if (request.sourceReferences().size() > 10) {
                throw ApiException.validation("来源材料最多10个");
            }
            for (SourceReferenceRequest reference : request.sourceReferences()) {
                references.add(validateReference(reference));
            }
        }
        return new NormalizedAssignment(name, requirements, request.hardDueAt(),
                request.minMembers(), request.maxMembers(), List.copyOf(references));
    }

    private SourceReferenceView validateReference(SourceReferenceRequest reference) {
        if (reference == null) {
            throw ApiException.validation("来源材料不能为null");
        }
        if (!"LINK".equals(reference.kind())) {
            throw ApiException.validation("当前阶段来源材料只支持LINK；文件引用在P05F接入");
        }
        String url = reference.url().strip();
        if (url.length() > 2048) {
            throw ApiException.validation("来源链接不能超过2048个字符");
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw ApiException.validation("来源链接必须为http/https URL");
            }
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("来源链接格式无效");
        }
        String title = reference.title().strip();
        if (title.isEmpty() || title.length() > 120) {
            throw ApiException.validation("来源材料标题必须为1～120个字符");
        }
        return new SourceReferenceView("LINK", url, title);
    }

    private enum Relation {
        TEACHER,
        STUDENT,
        NONE
    }

    private record NormalizedAssignment(
            String name,
            String requirements,
            java.time.Instant hardDueAt,
            int minMembers,
            int maxMembers,
            List<SourceReferenceView> references
    ) {
        private NormalizedAssignment {
            references = List.copyOf(references);
        }
    }
}
