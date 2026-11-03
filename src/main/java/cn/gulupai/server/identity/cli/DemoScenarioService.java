package cn.gulupai.server.identity.cli;

import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.community.api.BoardRequests;
import cn.gulupai.server.community.api.ChatRequests;
import cn.gulupai.server.community.application.BoardService;
import cn.gulupai.server.community.application.ChatService;
import cn.gulupai.server.course.api.AssignmentView;
import cn.gulupai.server.course.api.CreateAssignmentRequest;
import cn.gulupai.server.course.application.AssignmentService;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.delivery.DeliveryRequests;
import cn.gulupai.server.delivery.DeliveryService;
import cn.gulupai.server.file.FileService;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.merge.MergeRequests;
import cn.gulupai.server.merge.MergeService;
import cn.gulupai.server.notice.api.NoticeRequests;
import cn.gulupai.server.notice.application.NoticeService;
import cn.gulupai.server.report.ReportRequests;
import cn.gulupai.server.report.ReportService;
import cn.gulupai.server.support.HelpSupportService;
import cn.gulupai.server.support.SupportRequests;
import cn.gulupai.server.support.OperatorGrantAdminService;
import cn.gulupai.server.task.api.TaskRequests;
import cn.gulupai.server.task.application.TaskService;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import cn.gulupai.server.team.api.TeamRequests;
import cn.gulupai.server.team.api.TeamViews;
import cn.gulupai.server.team.application.TeamService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 场景 A～J 的初始数据。现场还要做的确认、验收、归档不在这里代做。
 * 收尾场景需要任务已经完成，当前没有不经过提交验收就能合法完成任务的入口，因此不预写完成状态。
 */
@Service
public class DemoScenarioService {

    private static final String MOBILE_A = "square_demo_full_mobile_a";
    private static final String MOBILE_B = "square_demo_full_mobile_b";
    private static final String DB_A = "square_demo_full_db";
    private static final String DB_B = "square_demo_full_db_b";
    private static final String TEACHER_A = "user_demo_full_teacher_01";
    private static final String TEACHER_B = "user_demo_full_teacher_02";
    private static final String STUDENT_01 = "user_demo_full_student_01";
    private static final String STUDENT_02 = "user_demo_full_student_02";
    private static final String STUDENT_03 = "user_demo_full_student_03";
    private static final String STUDENT_04 = "user_demo_full_student_04";
    private static final String STUDENT_08 = "user_demo_full_student_08";
    private static final String ASSISTANT_A = "user_demo_full_assistant_01";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private final AssignmentService assignments;
    private final TeamService teams;
    private final TaskService tasks;
    private final ChatService chats;
    private final BoardService boards;
    private final NoticeService notices;
    private final HelpSupportService support;
    private final MergeService merges;
    private final DeliveryService deliveries;
    private final ReportService reports;
    private final FileService files;
    private final OperatorGrantAdminService grants;
    private final TeachingRepository teaching;
    private final SquareRepository squares;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DemoScenarioService(AssignmentService assignments, TeamService teams, TaskService tasks,
                               ChatService chats, BoardService boards, NoticeService notices,
                               HelpSupportService support, MergeService merges, DeliveryService deliveries,
                               ReportService reports, FileService files,
                               OperatorGrantAdminService grants, TeachingRepository teaching,
                               SquareRepository squares, UserRepository users, JdbcTemplate jdbc, Clock clock) {
        this.assignments = assignments;
        this.teams = teams;
        this.tasks = tasks;
        this.chats = chats;
        this.boards = boards;
        this.notices = notices;
        this.support = support;
        this.merges = merges;
        this.deliveries = deliveries;
        this.reports = reports;
        this.files = files;
        this.grants = grants;
        this.teaching = teaching;
        this.squares = squares;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void seed() {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE name = '演示快速组'", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        teaching.ensureActive(MOBILE_A, TEACHER_A);
        teaching.ensureActive(MOBILE_B, TEACHER_A);
        teaching.ensureActive(DB_A, TEACHER_B);
        teaching.ensureActive(DB_B, TEACHER_B);
        join(MOBILE_A, ASSISTANT_A, "01", "02", "03", "04");
        join(MOBILE_B, ASSISTANT_A, "05", "06", "07");
        join(DB_A, "user_demo_full_assistant_02", "09", "10", "11", "12");
        join(DB_B, "user_demo_full_assistant_02", "13", "14", "15", "16");

        Instant due = clock.instant().plus(Duration.ofDays(90));
        AssignmentView assignment = assignments.create(user(TEACHER_A), MOBILE_A,
                new CreateAssignmentRequest("演示作业", "完成可演示的课程项目", due, 2, 6, List.of()),
                "demo-full-assignment", "/demo-seed/assignments/mobile");
        TeamViews.ProjectView quick = teams.createProject(user(STUDENT_01),
                new TeamRequests.CreateProjectRequest(MOBILE_A, assignment.id(), "QUICK", "演示快速组",
                        null, 4, "周三", List.of(), null),
                "demo-full-quick", "/demo-seed/projects/quick");
        TeamViews.ProjectView detailed = teams.createProject(user(STUDENT_02),
                new TeamRequests.CreateProjectRequest(MOBILE_A, assignment.id(), "DETAILED", "演示岗位组",
                        "用于正式分工和自检", 3, "周四", List.of(
                        new TeamRequests.RoleInput("dev", "开发", "实现功能", 1),
                        new TeamRequests.RoleInput("qa", "测试", "验收功能", 2)), "dev"),
                "demo-full-detailed", "/demo-seed/projects/detailed");

        var quickInvite = teams.invite(user(STUDENT_01), quick.id(),
                new TeamRequests.InviteRequest(quick.revision(), STUDENT_04, "加入快速组担任独立验收", null),
                "demo-full-quick-reviewer", "/demo-seed/invitations/quick-reviewer");
        teams.respondInvitation(user(STUDENT_04), quickInvite.invitation().id(),
                new TeamRequests.InvitationResponseRequest(1, "ACCEPT", null),
                "demo-full-quick-reviewer-accept", "/demo-seed/invitations/quick-reviewer/accept");

        String stage = quick.stages().getFirst().id();
        var blocked = tasks.create(user(STUDENT_01), quick.id(), ordinary("前置任务", due, stage, List.of()),
                "demo-full-task-block", "/demo-seed/tasks/block");
        tasks.create(user(STUDENT_01), quick.id(), ordinary("待认领任务", due, stage,
                        List.of(new TaskRequests.DependencyInput(blocked.id(), "BEFORE_START"))),
                "demo-full-task-todo", "/demo-seed/tasks/todo");

        String qa = detailed.roles().stream().filter(role -> "测试".equals(role.roleName()))
                .findFirst().orElseThrow().id();
        TeamViews.InvitationView accepted = teams.invite(user(STUDENT_02), detailed.id(),
                new TeamRequests.InviteRequest(detailed.revision(), STUDENT_03, "加入测试岗", qa),
                "demo-full-invite-member", "/demo-seed/invitations/member");
        teams.respondInvitation(user(STUDENT_03), accepted.invitation().id(),
                new TeamRequests.InvitationResponseRequest(1, "ACCEPT", null),
                "demo-full-accept-member", "/demo-seed/invitations/member/accept");
        tasks.create(user(STUDENT_02), detailed.id(), assessed(detailed.stages().getFirst().id(), due),
                "demo-full-assessed", "/demo-seed/tasks/assessed");

        // G 收尾场景：走正式认领、提交、独立验收和复盘，不直接改任务状态。
        var blockedTask = tasks.task(user(STUDENT_01), blocked.id());
        var claimed = tasks.claim(user(STUDENT_01), blocked.id(),
                new TaskRequests.ClaimRequest(blockedTask.revision(), true),
                "demo-full-claim-complete", "/demo-seed/tasks/complete/claim");
        String criterionId = claimed.criteria().getFirst().id();
        var reviewInvite = deliveries.invite(user(STUDENT_01), blocked.id(),
                new DeliveryRequests.InvitationCreateRequest(STUDENT_04, claimed.revision()),
                "demo-full-review-invite", "/demo-seed/delivery/review-invite");
        deliveries.respondInvitation(user(STUDENT_04), reviewInvite.id(),
                new DeliveryRequests.InvitationResponseRequest(1, "ACCEPT", null),
                "demo-full-review-accept", "/demo-seed/delivery/review-accept");
        var submitted = deliveries.submit(user(STUDENT_01), blocked.id(),
                new DeliveryRequests.SubmitRequest(tasks.task(user(STUDENT_01), blocked.id()).revision(),
                        List.of(), "演示任务已完成，等待独立验收", List.of(), null, null),
                "demo-full-submit", "/demo-seed/delivery/submit");
        deliveries.accept(user(STUDENT_04), submitted.id(),
                new DeliveryRequests.ReviewRequest(1, List.of(),
                        List.of(new DeliveryRequests.CheckInput(null, criterionId, true)), List.of()),
                "demo-full-accept", "/demo-seed/delivery/accept");
        var quickClosed = teams.project(user(STUDENT_01), quick.id());
        reports.lifecycle(user(STUDENT_01), quick.id(),
                new ReportRequests.LifecycleAction(quickClosed.revision(), "ENTER_CLOSING", null, null),
                "demo-full-closing", "/demo-seed/projects/quick/closing");
        reports.saveReflection(user(STUDENT_01), quick.id(),
                new ReportRequests.PersonalReflection(0, "完成演示任务", "掌握正式验收流程",
                        "现场协调时间较紧", "提前安排验收"),
                "demo-full-reflection", "/demo-seed/projects/quick/reflection");

        for (int i = 1; i <= 51; i++) {
            chats.send(user(STUDENT_01), quick.id(), new ChatRequests.Send(
                    "demo-chat-" + i, "演示历史消息 " + i, List.of(), List.of()),
                    "demo-full-chat-" + i, "/demo-seed/chat/" + i);
        }
        boards.createTopic(user(STUDENT_01), quick.id(), new BoardRequests.CreateTopic(
                "演示留言", "这里留一条待现场回复的主题", "QUESTION", List.of(), List.of()),
                "demo-full-topic", "/demo-seed/topics");
        notices.create(user(TEACHER_A), MOBILE_A, new NoticeRequests.Create(
                "演示公告", "请在现场完成知悉。", "TEACHER", null),
                "demo-full-notice", "/demo-seed/notices");

        ensureRoutes();
        jdbc.update("""
                UPDATE support_route SET receiver_user_id='user_demo_full_operator'
                WHERE id='route_support'
                """);
        support.createTicket(user(STUDENT_01), new SupportRequests.CreateTicket(
                "demo-ticket-1", "route_support", "OPERATION", "演示求助",
                "现场可以继续回复、解决或重开", null,
                new SupportRequests.TicketContext("APPLICATION", null, "NETWORK_ERROR", "0.59.0"),
                List.of()),
                "demo-full-ticket", "/demo-seed/tickets");
        uploadPng(user(STUDENT_01));

        merges.create(user(TEACHER_A), merge(MOBILE_A, MOBILE_B, "演示投票期"),
                "demo-full-merge-mobile", "/demo-seed/merges/mobile");
        var expiring = merges.create(user(TEACHER_B), merge(DB_A, DB_B, "演示即将到期"),
                "demo-full-merge-db", "/demo-seed/merges/db");
        jdbc.update("UPDATE square_merge SET voting_ends_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC),
                expiring.id());

        TeamViews.ProjectView quickNow = teams.project(user(STUDENT_01), quick.id());
        TeamViews.InvitationView expired = teams.invite(user(STUDENT_01), quick.id(),
                new TeamRequests.InviteRequest(quickNow.revision(), STUDENT_08, "这张邀请会过期", null),
                "demo-full-invite-expired", "/demo-seed/invitations/expired");
        Instant past = clock.instant().minus(Duration.ofHours(1));
        jdbc.update("UPDATE team_invitation SET expires_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(past, ZoneOffset.UTC), expired.invitation().id());
        jdbc.update("UPDATE seat_reservation SET expires_at = ? WHERE invitation_id = ?",
                OffsetDateTime.ofInstant(past, ZoneOffset.UTC), expired.invitation().id());
        jdbc.update("""
                INSERT INTO cursor_snapshot
                    (id, token_hash, user_id, subject_type, subject_key, scope_key, query_hash, epoch,
                     ids_json, total, position, expires_at)
                VALUES ('cursor_demo_expired', ?, ?, 'USER', ?, 'demo-squares', ?, 1, '[]'::jsonb, 0, 0, ?)
                """, Hashing.sha256Hex("demo-cursor-token"), STUDENT_01, STUDENT_01,
                Hashing.sha256Hex("demo-cursor-query"),
                OffsetDateTime.ofInstant(past, ZoneOffset.UTC));
        grants.grant(ASSISTANT_A, "HELP_CONTENT_WRITE", "GLOBAL", null, true, false, "cli");
        grants.grant(ASSISTANT_A, "HELP_CONTENT_WRITE", "GLOBAL", null, false, false, "cli");
    }

    private MergeRequests.Create merge(String source, String target, String reason) {
        return new MergeRequests.Create(source, target, revision(source), revision(target), reason, List.of());
    }

    private int revision(String squareId) {
        Integer revision = jdbc.queryForObject("SELECT revision FROM square WHERE id = ?", Integer.class, squareId);
        return revision == null ? 1 : revision;
    }

    private void join(String squareId, String assistant, String... studentSuffixes) {
        squares.ensureMembership(squareId, assistant);
        for (String suffix : studentSuffixes) {
            squares.ensureMembership(squareId, "user_demo_full_student_" + suffix);
        }
    }

    private TaskRequests.CreateTaskRequest ordinary(String title, Instant due, String stageId,
                                                    List<TaskRequests.DependencyInput> dependencies) {
        return new TaskRequests.CreateTaskRequest(title, "现场再认领或开始", "ORDINARY", stageId,
                List.of(new TaskRequests.CriterionInput("可以演示")), dependencies, due, null);
    }

    private TaskRequests.CreateTaskRequest assessed(String stageId, Instant due) {
        TaskRequests.UnitInput unit = new TaskRequests.UnitInput("login", "登录页面", "10.00", List.of(
                new TaskRequests.ShareInput(STUDENT_02, "6.00", "主要部分"),
                new TaskRequests.ShareInput(STUDENT_03, "4.00", "配合部分")),
                List.of(new TaskRequests.CriterionInput("可以演示登录和退出")));
        return new TaskRequests.CreateTaskRequest("正式分工", "约定尚未全部确认", "ASSESSED", stageId,
                List.of(), List.of(), null, new TaskRequests.AssessedPlanInput(STUDENT_02, due, List.of(unit),
                new TaskRequests.ReviewerPolicyInput(STUDENT_02, "SELF_CHECK")));
    }

    private void ensureRoutes() {
        jdbc.update("""
                INSERT INTO support_route(id, type, name, available, category) VALUES
                ('route_support','SUPPORT','平台使用求助',true,'OPERATION'),
                ('route_course','COURSE','课程与教学求助',true,'COURSE_REQUIREMENT'),
                ('route_grades','GRADES','成绩与评价求助',true,'GRADES')
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void uploadPng(AuthenticatedUser principal) {
        FileService.IntentView intent = files.createIntent(principal, new FileService.IntentRequest(
                "SUPPORT_ATTACHMENT", "demo.png", "image/png", PNG.length, null, null, null, null),
                "demo-full-png", "/demo-seed/files");
        files.receive(intent.uploadToken(), "image/png", new ByteArrayInputStream(PNG));
        files.complete(principal, intent.file().id(), new FileService.CompletionRequest(1, intent.uploadToken()),
                "demo-full-png-done", "/demo-seed/files/complete");
    }

    private AuthenticatedUser user(String id) {
        UserRow row = users.findById(id).orElseThrow();
        List<Role> roles = users.findRoles(id);
        return new AuthenticatedUser(row.id(), "demo-seed", row.loginId(), row.schoolId(),
                row.displayName(), row.identityStatus().name(), roles, false);
    }
}
