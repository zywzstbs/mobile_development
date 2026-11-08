package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.pagination.CursorService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.platform.api.PlatformViews;
import cn.gulupai.server.platform.domain.ManagementAuditEvent;
import cn.gulupai.server.platform.domain.PlatformCapability;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class PlatformReadService {

    private static final Logger log = LoggerFactory.getLogger(PlatformReadService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final int SNAPSHOT_LIMIT = 5000;
    private static final int DIRECTORY_LIMIT = 200;
    /** 目录遇到无法准确投影的残缺授权时的固定对外说明，具体事实只进内部诊断。 */
    private static final String DIRECTORY_PENDING_VERIFICATION = "管理员目录存在待核对的授权，请联系平台维护人员";

    private final PlatformAuthorizationService authorizationService;
    private final PlatformRepository repository;
    private final CursorService cursorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformReadService(PlatformAuthorizationService authorizationService, PlatformRepository repository,
                               CursorService cursorService, ObjectMapper objectMapper, Clock clock) {
        this.authorizationService = authorizationService;
        this.repository = repository;
        this.cursorService = cursorService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 总览按字段检查能力。{@code PLATFORM_ACCOUNT_READ} 只是入口资格：
     * 三个计数字段各自需要对应能力，缺任何一个都明确拒绝而不是把无权计数置 0；
     * 计数能力齐全但没有审计读取能力时，{@code recentOperations} 返回空数组，
     * 含义是“当前可见操作集合为空”，不声称全平台没有事件。
     */
    public PlatformViews.DashboardView dashboard(AuthenticatedUser principal) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_ACCOUNT_READ);
        boolean platform = access.grant().platform();
        String grantId = access.grant().id();
        List<String> capabilities = access.grant().capabilities();
        List<String> missing = new ArrayList<>();
        if (!capabilities.contains(PlatformCapability.PLATFORM_TEACHER_REVIEW.wireName())) {
            missing.add(PlatformCapability.PLATFORM_TEACHER_REVIEW.wireName());
        }
        if (!capabilities.contains(PlatformCapability.PLATFORM_COURSE_GOVERNANCE.wireName())) {
            missing.add(PlatformCapability.PLATFORM_COURSE_GOVERNANCE.wireName());
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN,
                    "总览统计权限不足：缺少" + String.join("、", missing) + "；账号目录等已授权接口不受影响");
        }
        List<PlatformViews.RecentOperationView> recent = capabilities.contains(
                PlatformCapability.PLATFORM_AUDIT_READ.wireName())
                ? repository.recentAudits(platform, grantId, 20).stream().map(this::recent).toList()
                : List.of();
        return new PlatformViews.DashboardView(new PlatformViews.CountsView(
                bounded(repository.countPendingApplications(platform, grantId)),
                bounded(repository.countPendingClaims(platform, grantId)),
                bounded(repository.countExceptions(platform, grantId))), recent);
    }

    public PlatformViews.PageView<PlatformViews.AccountItem> users(AuthenticatedUser principal, Integer limit,
                                                                   String query, String status, String cursor) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_ACCOUNT_READ);
        int pageSize = AdminTexts.limit(limit);
        String text = AdminTexts.query(query);
        String accountStatus = accountFilter(status);
        List<String> ids = cap(repository.userIds(access.grant().platform(), access.grant().id(), accountStatus, text));
        return page(access, "users", filterKey(accountStatus, text, "", "", pageSize), pageSize, cursor, ids,
                slice -> accounts(access, slice).stream().map(row -> new PlatformViews.AccountItem(
                        row.id(), row.displayName(), row.loginId(), row.schoolId(), row.accountStatus(),
                        row.identityStatus(), row.roles(), row.platformCapabilities(), row.summary())).toList());
    }

    public PlatformViews.AccountDetail user(AuthenticatedUser principal, String userId) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_ACCOUNT_READ);
        AdminTexts.requireId(userId, "userId");
        PlatformRepository.ManagedUser user = repository.findUser(userId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        authorizationService.requireSchool(access, user.schoolId());
        boolean platform = access.grant().platform();
        String grantId = access.grant().id();
        Instant now = clock.instant();
        AccountProjection projected = project(user.id(), user.displayName(), user.loginId(), user.schoolId(),
                user.accountStatus(), user.identityStatus(), user.disabled(), user.revision(),
                repository.activeGrantCount(platform, grantId, user.id(), now),
                repository.teachingCount(platform, grantId, user.id()));
        // 课程关系可能跨校：按课程真实 school_id 过滤，摘要计数与明细使用同一范围。
        List<PlatformViews.CourseGrantView> grants = repository.activeGrantsForUser(platform, grantId, userId, now)
                .stream()
                .map(row -> new PlatformViews.CourseGrantView(row.squareId(), row.courseName(),
                        readCapabilities(row.capabilitiesJson()), row.status(), row.expiresAt()))
                .toList();
        List<PlatformViews.TeachingCourseView> teaching = repository.teachingForUser(platform, grantId, userId)
                .stream()
                .map(row -> new PlatformViews.TeachingCourseView(row.squareId(), row.fullCourseName(),
                        row.claimStatus()))
                .toList();
        List<PlatformViews.ManagementRecordView> records = repository.managementForUser(userId).stream()
                .map(row -> new PlatformViews.ManagementRecordView(row.id(),
                        AdminAuditMapping.action(row.action(), "USER"),
                        AdminAuditMapping.result(row.action(), "USER"), row.at()))
                .toList();
        return new PlatformViews.AccountDetail(new PlatformViews.AccountUser(
                projected.id(), projected.displayName(), projected.loginId(), projected.schoolId(),
                projected.accountStatus(), projected.identityStatus(), projected.roles(),
                projected.platformCapabilities(), projected.summary(),
                AccountEligibility.restorationEligible(user), user.revision()),
                grants, teaching, records);
    }

    public PlatformViews.PageView<PlatformViews.TeacherApplicationItem> teacherApplications(
            AuthenticatedUser principal, String status, Integer limit, String cursor) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_TEACHER_REVIEW);
        int pageSize = AdminTexts.limit(limit);
        String apiStatus = teacherFilter(status);
        String dbStatus = "ALL".equals(apiStatus) ? null : "APPROVED".equals(apiStatus) ? "ACTIVE" : apiStatus;
        List<String> ids = cap(repository.applicationIds(access.grant().platform(), access.grant().id(), dbStatus));
        return page(access, "teacher-applications", filterKey(apiStatus, "", "", "", pageSize), pageSize, cursor, ids,
                slice -> applicationItems(access, slice));
    }

    public PlatformViews.TeacherApplicationDetail teacherApplication(AuthenticatedUser principal, String applicationId) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_TEACHER_REVIEW);
        AdminTexts.requireId(applicationId, "applicationId");
        PlatformRepository.ApplicationRow row = repository.applicationsByIds(List.of(applicationId)).stream()
                .findFirst().orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        authorizationService.requireSchool(access, row.schoolId());
        return new PlatformViews.TeacherApplicationDetail(row.id(), row.userId(), name(row.applicantName()),
                row.submittedAt(), toApiStatus(row.status()), row.revision(), row.schoolId(),
                row.teacherApplication() == null ? "" : row.teacherApplication(), history(row));
    }

    public PlatformViews.PageView<PlatformViews.CourseItem> courses(AuthenticatedUser principal, Integer limit,
                                                                    String schoolId, String termId, String query,
                                                                    String cursor) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_COURSE_GOVERNANCE);
        int pageSize = AdminTexts.limit(limit);
        String school = AdminTexts.optionalId(schoolId, "schoolId");
        String term = AdminTexts.optionalId(termId, "termId");
        String text = AdminTexts.query(query);
        if (school != null) {
            authorizationService.requireSchool(access, school);
        }
        List<String> ids = cap(repository.courseIds(access.grant().platform(), access.grant().id(), school, term, text));
        return page(access, "courses", filterKey(school, term, text, "", pageSize), pageSize, cursor, ids,
                slice -> ordered(slice, repository.coursesByIds(access.grant().platform(), access.grant().id(), slice),
                        PlatformRepository.CourseRow::id).stream()
                        .map(this::courseItem).toList());
    }

    public PlatformViews.CourseDetail course(AuthenticatedUser principal, String squareId) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_COURSE_GOVERNANCE);
        AdminTexts.requireId(squareId, "squareId");
        String schoolId = repository.squareSchool(squareId);
        if (schoolId == null) {
            throw new ApiException(ApiCode.RESOURCE_NOT_FOUND);
        }
        authorizationService.requireSchool(access, schoolId);
        PlatformRepository.CourseRow course = repository.course(squareId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        List<PlatformViews.ClaimItem> claims = repository.claimsForSquare(squareId).stream()
                .map(row -> new PlatformViews.ClaimItem(row.id(), row.teacherId(), name(row.teacherName()),
                        row.message(), row.status(), row.submittedAt(), row.revision()))
                .toList();
        List<PlatformViews.AssistantGrantItem> grants = repository.grantsForSquare(squareId).stream()
                .map(row -> new PlatformViews.AssistantGrantItem(row.id(), row.userId(), name(row.userName()),
                        readCapabilities(row.capabilitiesJson()), row.status(), row.expiresAt(), row.revision(),
                        "ACTIVE".equals(row.status()) && row.expiresAt().isAfter(now)))
                .toList();
        return new PlatformViews.CourseDetail(new PlatformViews.CourseCore(
                course.id(), course.squareNo(), course.name(), course.schoolId(), course.termId(),
                blank(course.teacherId()), blank(course.teacherName())), claims, grants);
    }

    public PlatformViews.PageView<PlatformViews.AuditItem> audits(AuthenticatedUser principal, Integer limit,
                                                                  String cursor) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_AUDIT_READ);
        int pageSize = AdminTexts.limit(limit);
        List<String> ids = cap(repository.auditIds(access.grant().platform(), access.grant().id()));
        // 游标作用域带版本：修复前生成的审计游标保存的是未过滤的全表 ID，必须使其失效。
        return page(access, "audit-v2", filterKey("", "", "", "", pageSize), pageSize, cursor, ids, slice ->
                ordered(slice, repository.auditsByIds(access.grant().platform(), access.grant().id(), slice),
                        PlatformRepository.AuditHead::id).stream()
                        .map(this::auditItem).toList());
    }

    /**
     * 超管目录是“当前全平台管理团队”的现态视图：首期只对全平台范围授权开放，
     * 学校范围操作者明确 403。每用户只返回一条，撤销后重新授予不会重复列出；
     * 已撤销用户保留最近一条历史行，状态显示 {@code REVOKED}。
     */
    public PlatformViews.SuperAdminList superAdmins(AuthenticatedUser principal) {
        PlatformAuthorizationService.Access access = authorizationService.require(
                principal, PlatformCapability.PLATFORM_SUPER_ADMIN_MANAGE);
        if (!access.grant().platform()) {
            throw new ApiException(ApiCode.FORBIDDEN, "超级管理员目录只对全平台范围授权开放");
        }
        List<PlatformRepository.SuperAdminRow> rows = repository.superAdmins();
        if (rows.size() > DIRECTORY_LIMIT) {
            throw new ApiException(ApiCode.COLLECTION_TOO_LARGE, "超级管理员目录超过可完整返回的规模");
        }
        return new PlatformViews.SuperAdminList(rows.stream().map(row -> new PlatformViews.SuperAdminItem(
                row.userId(), name(row.displayName()), directoryStatus(row), row.grantedAt(),
                blank(row.grantedBy()), row.revision())).toList());
    }

    private List<PlatformViews.TeacherApplicationItem> applicationItems(
            PlatformAuthorizationService.Access access, List<String> ids) {
        return ordered(ids, repository.applicationsByIds(access.grant().platform(), access.grant().id(), ids),
                PlatformRepository.ApplicationRow::id).stream()
                .map(row -> new PlatformViews.TeacherApplicationItem(row.id(), row.userId(), name(row.applicantName()),
                        row.submittedAt(), toApiStatus(row.status()), row.revision(), row.schoolId()))
                .toList();
    }

    private List<AccountProjection> accounts(PlatformAuthorizationService.Access access, List<String> ids) {
        Instant now = clock.instant();
        boolean platform = access.grant().platform();
        String grantId = access.grant().id();
        return ordered(ids, repository.usersByIds(platform, grantId, ids), PlatformRepository.UserListRow::id).stream()
                .map(row -> project(row.id(), row.displayName(), row.loginId(), row.schoolId(),
                        row.accountStatus(), row.identityStatus(), row.disabled(), row.revision(),
                        repository.activeGrantCount(platform, grantId, row.id(), now),
                        repository.teachingCount(platform, grantId, row.id())))
                .toList();
    }

    private AccountProjection project(String id, String displayName, String loginId, String schoolId,
                                      String accountStatus, String identityStatus, boolean disabled, int revision,
                                      int grants, int teaching) {
        String projected = disabled && "ACTIVE".equals(accountStatus) ? "DISABLED" : accountStatus;
        return new AccountProjection(id, name(displayName), loginId, schoolId, projected, identityStatus,
                repository.rolesOf(id), repository.platformCapabilities(id), summary(grants, teaching), revision);
    }

    private <T> PlatformViews.PageView<T> page(PlatformAuthorizationService.Access access, String name, String filter,
                                               int limit, String cursor, List<String> freshIds,
                                               Function<List<String>, List<T>> load) {
        String scope = "p66:" + name + ":" + access.grant().id();
        String queryHash = Hashing.sha256Hex(filter);
        List<String> ordered;
        int position;
        // null 只表示“本次请求正在建立首页快照”。续页必须继承原快照的绝对到期时间，
        // 否则每次续页都会重新获得完整 TTL，把同一快照无限延长。
        Instant snapshotExpiresAt = null;
        if (cursor == null || cursor.isBlank()) {
            ordered = freshIds;
            position = 0;
        } else {
            var resolved = cursorService.resolve(AdminCursorCodec.decode(cursor), access.actorId(), scope, queryHash,
                    access.grant().revision());
            ordered = resolved.ids();
            position = resolved.position();
            snapshotExpiresAt = resolved.expiresAt();
            if (snapshotExpiresAt == null) {
                // 续页无法确认原到期点时按失效处理，绝不给旧快照重新续期。
                throw new ApiException(ApiCode.CURSOR_EXPIRED);
            }
            // 续页时按当前范围重算可见 ID。授权范围收缩、账号转校、申请/课程状态变化后，
            // 旧快照保存的 ID 可能已不可见；此时让游标失效，客户端从首页重读，
            // 而不是继续返回旧 total 或越权行。
            if (!new HashSet<>(freshIds).containsAll(ordered)) {
                throw new ApiException(ApiCode.CURSOR_EXPIRED);
            }
        }
        int to = Math.min(position + limit, ordered.size());
        if (position > ordered.size()) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        List<String> slice = ordered.subList(position, to);
        boolean hasMore = to < ordered.size();
        String next = "";
        if (hasMore) {
            String nextToken = snapshotExpiresAt == null
                    ? cursorService.create(access.actorId(), scope, queryHash, access.grant().revision(), ordered, to)
                    : cursorService.create(access.actorId(), scope, queryHash, access.grant().revision(), ordered, to,
                            snapshotExpiresAt);
            next = AdminCursorCodec.encode(nextToken);
        }
        return new PlatformViews.PageView<>(load.apply(slice), hasMore, next, ordered.size());
    }

    private PlatformViews.CourseItem courseItem(PlatformRepository.CourseRow row) {
        return new PlatformViews.CourseItem(row.id(), row.name(), row.squareNo(), row.schoolId(), row.termId(),
                blank(row.teacherId()), blank(row.teacherName()), row.claimStatus(), row.pendingClaims());
    }

    private PlatformViews.RecentOperationView recent(PlatformRepository.AuditHead row) {
        String action = AdminAuditMapping.action(row.action(), row.resourceType());
        return new PlatformViews.RecentOperationView(row.id(), action, AdminAuditMapping.targetType(row.resourceType()),
                blank(row.resourceId()), AdminAuditMapping.summary(action),
                AdminAuditMapping.result(row.action(), row.resourceType()), row.at());
    }

    private PlatformViews.AuditItem auditItem(PlatformRepository.AuditHead row) {
        String actorId = blank(row.actorId());
        String actorName = row.actorName() == null || row.actorName().isBlank()
                ? ("cli".equals(actorId) ? "cli" : "未知") : row.actorName();
        return new PlatformViews.AuditItem(row.id(), actorId, actorName,
                AdminAuditMapping.roleLabel(actorId, row.actorRoles()),
                AdminAuditMapping.action(row.action(), row.resourceType()),
                AdminAuditMapping.targetType(row.resourceType()), blank(row.resourceId()), scopeLabel(row),
                reason(row), AdminAuditMapping.result(row.action(), row.resourceType()), blank(row.requestId()),
                row.at());
    }

    /**
     * 范围字段只表达真实目标范围：{@code PLATFORM} 表示全平台授权的授撤事件，具体学校 ID
     * 表示资源所属学校。修复前用操作者学校兜底的做法已删除；无法确定范围的事件已被
     * SQL 白名单与范围条件挡在列表之外，因此这里不再出现 {@code UNSCOPED}。
     */
    private static String scopeLabel(PlatformRepository.AuditHead row) {
        String scope = blank(row.scopeSchool());
        return scope.length() > 200 ? scope.substring(0, 200) : scope;
    }

    /** 理由可见性由事件分类决定；未获准返回原文的理由统一为空字符串，不做关键词替换。 */
    private static String reason(PlatformRepository.AuditHead row) {
        if (!ManagementAuditEvent.reasonVisible(row.action(), row.resourceType())) {
            return "";
        }
        return blank(row.reason());
    }

    private List<PlatformViews.TeacherHistoryItem> history(PlatformRepository.ApplicationRow row) {
        if (row.reviewedAt() == null) {
            return List.of();
        }
        String action = "REJECTED".equals(row.status()) ? "REJECTED" : "APPROVED";
        String actorName = row.reviewedBy() == null || row.reviewedBy().isBlank()
                ? "未知" : repository.displayName(row.reviewedBy());
        if (actorName == null || actorName.isBlank()) {
            actorName = "未知";
        }
        return List.of(new PlatformViews.TeacherHistoryItem(row.id() + "_h1", action, actorName,
                blank(row.reviewReason()), row.reviewedAt()));
    }

    private static String accountFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.strip();
        if (!"ACTIVE".equals(value) && !"DISABLED".equals(value) && !"PENDING_REVIEW".equals(value)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "status 只能是 ACTIVE、DISABLED 或 PENDING_REVIEW");
        }
        return value;
    }

    private static String teacherFilter(String status) {
        if (status == null || status.isBlank()) {
            return "PENDING_REVIEW";
        }
        String value = status.strip();
        if (!"PENDING_REVIEW".equals(value) && !"APPROVED".equals(value) && !"REJECTED".equals(value)
                && !"ALL".equals(value)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "status 只能是 PENDING_REVIEW、APPROVED、REJECTED 或 ALL");
        }
        return value;
    }

    private static String toApiStatus(String stored) {
        return "ACTIVE".equals(stored) ? "APPROVED" : stored;
    }

    /**
     * A14 目录状态的唯一入口，按“撤销 / 真实停用 / 完整有效”三类分别投影，其余残缺记录
     * 不做近似表达：一条无法准确投影的 ACTIVE 授权会让整份目录返回不可就绪。
     *
     * <p>有效性按选中授权行自身的事实判断，不套用 {@code isPlatformRecoveryAdmin}：
     * 合法学校范围管理员只要角色/能力/范围齐全仍显示 ACTIVE，恢复全平台的能力另行判断。
     */
    private String directoryStatus(PlatformRepository.SuperAdminRow row) {
        if ("REVOKED".equals(row.grantStatus())) {
            return "REVOKED";
        }
        if (row.disabled() || "DISABLED".equals(row.accountStatus())) {
            // 授权仍在但账号确实停用：表达真实停用，不把待审/驳回也算成停用。
            return "DISABLED";
        }
        List<String> missing = missingDirectoryFacts(row);
        if (missing.isEmpty()) {
            return "ACTIVE";
        }
        // 残缺 ACTIVE 授权无法准确投影成任何既有状态：整份目录明确不可就绪，
        // 只记录异常目标与缺失事实代码，不输出密码、凭据或目录成员资料。
        log.warn("超管目录存在待核对的授权 grantId={} userId={} missing={}",
                row.grantId(), row.userId(), missing);
        throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, DIRECTORY_PENDING_VERIFICATION);
    }

    /** ACTIVE 投影所需的最低事实；返回空表示可以显示 ACTIVE。 */
    private static List<String> missingDirectoryFacts(PlatformRepository.SuperAdminRow row) {
        List<String> missing = new ArrayList<>();
        if (!"ACTIVE".equals(row.accountStatus()) || row.disabled()) {
            // 待审/驳回不是“停用”：它们属于资格异常，不能投影成 ACTIVE 或 DISABLED。
            missing.add("ACCOUNT_NOT_USABLE");
        }
        if (!"VERIFIED".equals(row.identityStatus())) {
            missing.add("IDENTITY_NOT_VERIFIED");
        }
        if (!"VERIFIED_ACTIVE".equals(row.eligibilitySource())) {
            missing.add("ELIGIBILITY_NOT_VERIFIED_ACTIVE");
        }
        if (!row.hasPassword()) {
            missing.add("CREDENTIAL_MISSING");
        }
        if (!row.roleHeld()) {
            missing.add("SUPER_ADMIN_ROLE_MISSING");
        }
        if (!row.hasCapabilities()) {
            missing.add("CAPABILITY_MISSING");
        }
        if (!row.hasScope()) {
            missing.add("SCOPE_MISSING");
        }
        return missing;
    }

    private static String summary(int grants, int teaching) {
        if (grants > 0 && teaching > 0) {
            return grants + "门课程有助理授权，负责" + teaching + "门课程";
        }
        if (grants > 0) {
            return grants + "门课程有助理授权";
        }
        if (teaching > 0) {
            return "当前负责" + teaching + "门课程";
        }
        return "";
    }

    private static String name(String value) {
        String text = blank(value);
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String filterKey(String a, String b, String c, String d, int limit) {
        return (a == null ? "" : a) + "|" + (b == null ? "" : b) + "|" + (c == null ? "" : c) + "|"
                + (d == null ? "" : d) + "|" + limit;
    }

    private static int bounded(int count) {
        if (count < 0 || count > 1_000_000) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "统计结果超出可返回范围");
        }
        return count;
    }

    private static List<String> cap(List<String> ids) {
        if (ids.size() > SNAPSHOT_LIMIT) {
            throw new ApiException(ApiCode.COLLECTION_TOO_LARGE, "结果超过5000条，需要缩小筛选");
        }
        return ids;
    }

    private List<String> readCapabilities(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (Exception ex) {
            throw new IllegalStateException("班级授权能力无法解析", ex);
        }
    }

    private static <T> List<T> ordered(List<String> ids, List<T> rows, Function<T, String> id) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T row : rows) {
            byId.put(id.apply(row), row);
        }
        List<T> ordered = new ArrayList<>();
        for (String itemId : ids) {
            T row = byId.get(itemId);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    private record AccountProjection(String id, String displayName, String loginId, String schoolId,
                                     String accountStatus, String identityStatus, List<String> roles,
                                     List<String> platformCapabilities, String summary, int revision) {
    }
}
