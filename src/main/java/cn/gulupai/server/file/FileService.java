package cn.gulupai.server.file;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.file.FileRepository.AuthorizationRow;
import cn.gulupai.server.file.FileRepository.StoredFileRow;
import cn.gulupai.server.support.SupportTicketAccess;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 上传意图、完成校验和下载授权。求助附件在无工单时仅本人可见；关联工单后按当前工单资格读取。
 */
@Service
public class FileService {

    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final Duration ORPHAN_AFTER = Duration.ofHours(24);
    private static final Set<String> TYPES = Set.of(
            FileSignatures.PNG, FileSignatures.JPEG, FileSignatures.WEBP, FileSignatures.PDF,
            FileSignatures.TEXT, FileSignatures.MARKDOWN, FileSignatures.DOCX);
    private static final Set<String> IMAGES = Set.of(
            FileSignatures.PNG, FileSignatures.JPEG, FileSignatures.WEBP);

    private final FileRepository files;
    private final FileStorage storage;
    private final FileProperties properties;
    private final TeamRepository teams;
    private final SquareRepository squares;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;
    private final SupportTicketAccess ticketAccess;

    public FileService(FileRepository files, FileStorage storage, FileProperties properties,
                       TeamRepository teams, SquareRepository squares, CurrentUserPolicy policy,
                       IdempotencyService idempotency, AuditService audit, Clock clock,
                       SupportTicketAccess ticketAccess) {
        this.files = files;
        this.storage = storage;
        this.properties = properties;
        this.teams = teams;
        this.squares = squares;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.ticketAccess = ticketAccess;
    }

    public IntentView createIntent(AuthenticatedUser principal, IntentRequest request,
                                  String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                IntentView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> createIntentOnce(principal, request),
                cached -> {
                    policy.requireActiveBusinessUser(principal);
                    authorizeScope(principal, request);
                    return cached;
                });
    }

    public FileView complete(AuthenticatedUser principal, String fileId, CompletionRequest request,
                            String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                FileView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> completeOnce(principal, fileId, request));
    }

    @Transactional(readOnly = true)
    public FileView metadata(AuthenticatedUser principal, String fileId) {
        policy.requireActiveBusinessUser(principal);
        StoredFileRow file = files.findFile(fileId).orElseThrow(ApiException::notFound);
        if (!canRead(principal.userId(), file)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return view(file);
    }

    public DownloadView authorizeDownload(AuthenticatedUser principal, String fileId,
                                         String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, Map.of(), List.of()),
                DownloadView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> authorizeDownloadOnce(principal, fileId),
                cached -> {
                    policy.requireActiveBusinessUser(principal);
                    StoredFileRow file = files.findFile(fileId).orElseThrow(ApiException::notFound);
                    if (!"READY".equals(file.status()) || !canRead(principal.userId(), file)) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    return cached;
                });
    }

    public void receive(String token, String contentType, InputStream body) {
        AuthorizationRow authorization = requireToken(token, "UPLOAD");
        StoredFileRow file = files.findFile(authorization.fileId()).orElseThrow(ApiException::notFound);
        if (!file.contentType().equals(mediaType(contentType))) {
            throw ApiException.validation("上传内容类型必须与申请一致");
        }
        if (!"UPLOADING".equals(file.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "该文件已经结束上传");
        }
        if (storage.incomingExists(file.id())) {
            try {
                FileStorage.StoredBytes incoming = storage.hashStream(body, file.declaredSize());
                FileStorage.StoredBytes existing;
                try (InputStream stored = java.nio.file.Files.newInputStream(storage.incoming(file.id()))) {
                    existing = storage.hashStream(stored, file.declaredSize());
                }
                if (incoming.size() == existing.size() && incoming.sha256().equals(existing.sha256())) {
                    if (file.receivedAt() == null) {
                        files.markReceived(file.id(), file.revision(), existing.size(), existing.sha256(),
                                clock.instant());
                    }
                    return;
                }
            } catch (java.io.IOException ex) {
                throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件暂时无法读取");
            }
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "不能用不同内容覆盖已接收的文件");
        }
        FileStorage.StoredBytes stored = storage.writeIncoming(file.id(), body, file.declaredSize());
        if (files.markReceived(file.id(), file.revision(), stored.size(), stored.sha256(), clock.instant()) != 1) {
            storage.deleteIncoming(file.id());
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
    }

    public DownloadPayload openDownload(String token) {
        AuthorizationRow authorization = requireToken(token, "DOWNLOAD");
        StoredFileRow file = files.findFile(authorization.fileId()).orElseThrow(ApiException::notFound);
        if (!"READY".equals(file.status()) || !storage.readyExists(file.id())) {
            throw new ApiException(ApiCode.NOT_FOUND);
        }
        if (!ticketAccess.accountActive(authorization.userId())) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        if (!canRead(authorization.userId(), file)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return new DownloadPayload(file.fileName(), file.contentType(), storage.openReady(file.id()));
    }

    @Transactional
    public void attachReference(String fileId, String resourceType, String resourceId, int resourceVersion) {
        StoredFileRow file = files.lockFile(fileId).orElseThrow(ApiException::notFound);
        if (!"READY".equals(file.status())) {
            throw ApiException.validation("文件尚未就绪，不能引用");
        }
        files.insertReference(UUID.randomUUID().toString(), fileId, resourceType, resourceId,
                resourceVersion, null, clock.instant());
    }

    public void reconcile() {
        storage.deleteUnexpectedIncoming(files.filesByStatus("UPLOADING").stream()
                .map(StoredFileRow::id).collect(java.util.stream.Collectors.toSet()));
        Instant now = clock.instant();
        for (StoredFileRow file : files.filesByStatus("UPLOADING")) {
            if (file.receivedAt() != null && !storage.incomingExists(file.id()) && !storage.readyExists(file.id())) {
                files.markFailed(file.id(), file.revision(), now);
            } else if (file.createdAt().plus(ORPHAN_AFTER).isBefore(now) && !files.plannedCleanup(file.id())) {
                files.insertCleanupPlan(UUID.randomUUID().toString(), file.id(), "ORPHAN_UPLOAD", now, now);
            }
        }
        for (StoredFileRow file : files.filesByStatus("READY")) {
            if (!storage.readyExists(file.id())) {
                files.markFailedIfUnreferenced(file.id(), now);
                continue;
            }
            if (file.readyAt() != null && file.readyAt().plus(ORPHAN_AFTER).isBefore(now)
                    && !files.referenced(file.id()) && !files.plannedCleanup(file.id())) {
                files.insertCleanupPlan(UUID.randomUUID().toString(), file.id(), "UNREFERENCED", now, now);
            }
        }
        for (FileRepository.CleanupPlanRow plan : files.duePlans(now)) {
            if (files.referenced(plan.resourceId())) {
                continue;
            }
            storage.deleteIncoming(plan.resourceId());
            storage.deleteReady(plan.resourceId());
            files.executePlan(plan.id(), now);
        }
    }

    private IntentView createIntentOnce(AuthenticatedUser principal, IntentRequest request) {
        String purpose = request.purpose();
        String contentType = request.contentType();
        if (!TYPES.contains(contentType)) {
            throw ApiException.validation("不支持的文件类型");
        }
        if ("SUPPORT_ATTACHMENT".equals(purpose) && !IMAGES.contains(contentType)) {
            throw ApiException.validation("求助附件只允许图片");
        }
        authorizeScope(principal, request);
        storage.ensureSpace(request.sizeBytes());
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        String token = Hashing.newToken();
        StoredFileRow row = new StoredFileRow(id, principal.userId(), purpose, blankToNull(request.projectId()),
                blankToNull(request.caseId()), blankToNull(request.ticketId()), safeName(request.fileName()),
                contentType, request.sizeBytes(), null, null, blankToNull(request.sha256()), "UPLOADING",
                now.plus(UPLOAD_TTL), null, null, 1, now);
        files.insertFile(row);
        files.insertAuthorization(UUID.randomUUID().toString(), id, principal.userId(), "UPLOAD",
                Hashing.sha256Hex(token), row.uploadExpiresAt(), now);
        audit.record(principal.userId(), "FILE_INTENT", "FILE", id, null, 1, null);
        String base = properties.getPublicBaseUrl().replaceAll("/$", "");
        return new IntentView(view(row), new UploadView("PUT", base + "/internal-file-transfer/" + token,
                Map.of("Content-Type", contentType), row.uploadExpiresAt()), token);
    }

    private FileView completeOnce(AuthenticatedUser principal, String fileId, CompletionRequest request) {
        StoredFileRow current = files.lockFile(fileId).orElseThrow(ApiException::notFound);
        if (!current.uploaderId().equals(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        AuthorizationRow authorization = files.findAuthorization(Hashing.sha256Hex(request.uploadToken()))
                .orElseThrow(() -> new ApiException(ApiCode.CODE_EXPIRED, "上传授权无效"));
        if (!authorization.fileId().equals(fileId) || !"UPLOAD".equals(authorization.kind())) {
            throw new ApiException(ApiCode.CODE_EXPIRED, "上传授权无效");
        }
        if (!authorization.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.CODE_EXPIRED, "上传授权已过期");
        }
        if ("READY".equals(current.status()) || "FAILED".equals(current.status())) {
            return view(files.findFile(fileId).orElseThrow());
        }
        if (current.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        Instant now = clock.instant();
        java.nio.file.Path source = storage.incomingExists(fileId) ? storage.incoming(fileId)
                : storage.readyExists(fileId) ? storage.ready(fileId) : null;
        boolean acceptable = current.receivedAt() != null
                && current.actualSize() != null
                && current.actualSize() == current.declaredSize()
                && (current.declaredSha256() == null || current.declaredSha256().equals(current.sha256()))
                && source != null
                && FileSignatures.matches(current.contentType(), current.fileName(), source);
        if (!acceptable) {
            files.markFailed(fileId, current.revision(), now);
            audit.record(principal.userId(), "FILE_FAILED", "FILE", fileId, current.revision(),
                    current.revision() + 1, null);
            return view(files.findFile(fileId).orElseThrow());
        }
        if (storage.incomingExists(fileId)) {
            storage.promote(fileId);
        }
        if (files.markReady(fileId, current.revision(), now) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        audit.record(principal.userId(), "FILE_READY", "FILE", fileId, current.revision(),
                current.revision() + 1, null);
        return view(files.findFile(fileId).orElseThrow());
    }

    private DownloadView authorizeDownloadOnce(AuthenticatedUser principal, String fileId) {
        StoredFileRow file = files.findFile(fileId).orElseThrow(ApiException::notFound);
        if (!"READY".equals(file.status())) {
            throw ApiException.validation("文件尚未就绪");
        }
        if (!canRead(principal.userId(), file)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(DOWNLOAD_TTL);
        String token = Hashing.newToken();
        files.insertAuthorization(UUID.randomUUID().toString(), fileId, principal.userId(), "DOWNLOAD",
                Hashing.sha256Hex(token), expiresAt, now);
        String base = properties.getPublicBaseUrl().replaceAll("/$", "");
        return new DownloadView(base + "/internal-file-transfer/" + token, expiresAt);
    }

    private void authorizeScope(AuthenticatedUser principal, IntentRequest request) {
        String purpose = request.purpose();
        boolean project = present(request.projectId());
        boolean caseId = present(request.caseId());
        boolean ticket = present(request.ticketId());
        switch (purpose) {
            case "DELIVERY_ARTIFACT" -> {
                if (!project || caseId || ticket) {
                    throw ApiException.validation("交付附件只能并且必须指定当前项目");
                }
                requireProjectMember(principal, request.projectId());
            }
            case "WORK_ARTIFACT" -> {
                if (caseId || ticket) {
                    throw ApiException.validation("作品文件不能指定事项或工单");
                }
                if (project) {
                    requireProjectMember(principal, request.projectId());
                }
            }
            case "SUPPORT_ATTACHMENT" -> {
                if (project || caseId) {
                    throw ApiException.validation("求助附件不能指定项目或事项");
                }
                if (ticket && !ticketAccess.canReadTicket(principal.userId(), request.ticketId())) {
                    throw new ApiException(ApiCode.FORBIDDEN);
                }
            }
            case "MATERIAL_PROOF" -> throw new ApiException(ApiCode.FORBIDDEN, "人员事项尚未接入，不能上传证明材料");
            default -> throw ApiException.validation("不支持的文件用途");
        }
    }

    private boolean canRead(String userId, StoredFileRow file) {
        return switch (file.purpose()) {
            case "DELIVERY_ARTIFACT" -> file.projectId() != null && isMember(file.projectId(), userId);
            case "WORK_ARTIFACT" -> userId.equals(file.uploaderId())
                    && (file.projectId() == null || isMember(file.projectId(), userId));
            case "SUPPORT_ATTACHMENT" -> file.ticketId() == null
                    ? userId.equals(file.uploaderId()) && ticketAccess.accountActive(userId)
                    : ticketAccess.canReadTicket(userId, file.ticketId());
            default -> false;
        };
    }

    private void requireProjectMember(AuthenticatedUser principal, String projectId) {
        ProjectRow project = teams.findProject(projectId).orElseThrow(ApiException::notFound);
        SquareRow square = squares.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
        if (!isMember(projectId, principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private boolean isMember(String projectId, String userId) {
        return teams.membership(projectId, userId).isPresent();
    }

    private static String mediaType(String contentType) {
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private AuthorizationRow requireToken(String token, String kind) {
        AuthorizationRow row = files.findAuthorization(Hashing.sha256Hex(token)).orElseThrow(ApiException::notFound);
        if (!kind.equals(row.kind()) || !row.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(ApiCode.CODE_EXPIRED, "文件授权已过期");
        }
        return row;
    }

    private FileView view(StoredFileRow file) {
        return new FileView(file.id(), file.purpose(), file.fileName(), file.contentType(), file.declaredSize(),
                file.status(), file.revision(), file.uploaderId(), file.createdAt());
    }

    private static String safeName(String fileName) {
        String name = fileName.strip();
        if (name.isEmpty() || name.length() > 200 || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.indexOf('\0') >= 0) {
            throw ApiException.validation("文件名不能包含路径");
        }
        return name;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return present(value) ? value : null;
    }

    public record IntentRequest(String purpose, String fileName, String contentType, Integer sizeBytes,
                               String sha256, String projectId, String caseId, String ticketId) {
    }

    public record CompletionRequest(Integer expectedRevision, String uploadToken) {
    }

    public record FileView(String id, String purpose, String fileName, String contentType, int sizeBytes,
                          String status, int revision, String createdBy, Instant createdAt) {
    }

    public record UploadView(String method, String url, Map<String, String> headers, Instant expiresAt) {
    }

    public record IntentView(FileView file, UploadView upload, String uploadToken) {
    }

    public record DownloadView(String url, Instant expiresAt) {
    }

    public record DownloadPayload(String fileName, String contentType, InputStream body) {
    }
}
