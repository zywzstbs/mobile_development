package cn.gulupai.server.team.application;

import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.team.api.TeamViews;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人待办统一聚合。每个来源直接查询当前业务状态，不从通知 outbox 或缓存推断；
 * ACTION 只发给当前确实可执行的人，WAITING 只发给本人事项正在等待别人的场景。
 * 稳定 todoId 由 type + 源资源 + 具体动作 + 接收人确定，revision 取来源版本。
 */
@Service
public class TodoQueryService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<String>>> ELECTORATES = new TypeReference<>() {
    };
    private static final Duration PEER_REVIEW_WINDOW = Duration.ofDays(14);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TodoQueryService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<TeamViews.TodoView> todos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        items.addAll(taskTodos(userId));
        items.addAll(agreementTodos(userId));
        items.addAll(changeProposalTodos(userId));
        items.addAll(reviewerInvitationTodos(userId));
        items.addAll(applicationTodos(userId));
        items.addAll(invitationTodos(userId));
        items.addAll(roleTodos(userId));
        items.addAll(removalVoteTodos(userId));
        items.addAll(memberChangeTodos(userId));
        items.addAll(materialRequestTodos(userId));
        items.addAll(personnelHandoffTodos(userId));
        items.addAll(squareMergeTodos(userId));
        items.addAll(peerReviewTodos(userId));
        items.addAll(gradeAppealTodos(userId));
        Map<String, TeamViews.TodoView> unique = new LinkedHashMap<>();
        for (TeamViews.TodoView item : items) {
            unique.putIfAbsent(item.id(), item);
        }
        return unique.values().stream()
                .sorted(java.util.Comparator.comparing(TeamViews.TodoView::updatedAt).reversed()
                        .thenComparing(TeamViews.TodoView::id))
                .toList();
    }

    private List<TeamViews.TodoView> taskTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        Map<String, TeamViews.TodoView> byTask = new LinkedHashMap<>();
        // 本人待验收优先。
        for (var row : jdbc.query("""
                SELECT t.id, t.title, t.revision, p.id AS project_id, p.assignment_id,
                       GREATEST(t.updated_at, sub.submitted_at) AS updated_at
                FROM submission sub
                JOIN task t ON t.id = sub.task_id
                JOIN project p ON p.id = t.project_id
                JOIN reviewer_arrangement ra ON ra.task_id = t.id AND ra.user_id = ? AND ra.status = 'ACCEPTED'
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE sub.status = 'PENDING'
                """, (rs, n) -> new SourceTodo("TASK", "TASK", rs.getString("id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("title"),
                "ACTION", instant(rs, "updated_at"), rs.getInt("revision"), "REVIEW"), userId, userId)) {
            byTask.put(row.resourceId(), row.view(userId, "REVIEW"));
        }
        // 本人当前责任任务尚未完成。
        for (var row : jdbc.query("""
                SELECT t.id, t.title, t.revision, p.id AS project_id, p.assignment_id, t.updated_at
                FROM task t
                JOIN project p ON p.id = t.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE t.owner_id = ? AND t.status IN ('TODO', 'IN_PROGRESS')
                  AND t.handover_blocked = false
                  AND (t.mode = 'ORDINARY'
                       OR EXISTS (SELECT 1 FROM agreement ag WHERE ag.task_id = t.id AND ag.status = 'ACTIVE'))
                """, (rs, n) -> new SourceTodo("TASK", "TASK", rs.getString("id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("title"),
                "ACTION", instant(rs, "updated_at"), rs.getInt("revision"), "WORK"), userId, userId)) {
            byTask.putIfAbsent(row.resourceId(), row.view(userId, "WORK"));
        }
        // 本人提交等待验收。
        for (var row : jdbc.query("""
                SELECT t.id, t.title, t.revision, p.id AS project_id, p.assignment_id,
                       sub.submitted_at AS updated_at
                FROM submission sub
                JOIN task t ON t.id = sub.task_id
                JOIN project p ON p.id = t.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE sub.submitted_by = ? AND sub.status = 'PENDING'
                """, (rs, n) -> new SourceTodo("TASK", "TASK", rs.getString("id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("title"),
                "WAITING", instant(rs, "updated_at"), rs.getInt("revision"), "SUBMITTED"), userId, userId)) {
            byTask.putIfAbsent(row.resourceId(), row.view(userId, "SUBMITTED"));
        }
        items.addAll(byTask.values());
        return items;
    }

    private List<TeamViews.TodoView> agreementTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT ag.id, ag.status, ag.version, ag.revision, ag.updated_at,
                       t.id AS task_id, t.title, p.id AS project_id, p.assignment_id, p.owner_id,
                       EXISTS (SELECT 1 FROM unit_share s JOIN delivery_unit u ON u.id = s.unit_id
                               WHERE u.task_id = t.id AND s.agreement_version = ag.version AND s.user_id = ?)
                       OR p.owner_id = ?
                       OR EXISTS (SELECT 1 FROM reviewer_arrangement ra
                                  WHERE ra.task_id = t.id AND ra.user_id = ?) AS required,
                       EXISTS (SELECT 1 FROM agreement_ack ack
                               WHERE ack.agreement_id = ag.id AND ack.user_id = ?) AS acked
                FROM agreement ag
                JOIN task t ON t.id = ag.task_id
                JOIN project p ON p.id = t.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE ag.status IN ('PENDING', 'CHANGES_REQUESTED')
                """, (rs, n) -> new AgreementSource(rs.getString("id"), rs.getString("task_id"),
                rs.getString("title"), rs.getString("project_id"), rs.getString("assignment_id"),
                rs.getString("owner_id"), rs.getBoolean("required"), rs.getBoolean("acked"),
                rs.getInt("revision"), instant(rs, "updated_at")),
                userId, userId, userId, userId, userId)) {
            if (row.required() && !row.acked()) {
                items.add(todo("AGREEMENT", "AGREEMENT", row.id(), row.projectId(), row.assignmentId(),
                        row.title() + " 正式约定待确认", "ACTION", row.updatedAt(), row.revision(),
                        userId, "ACK"));
            } else if (userId.equals(row.ownerId())) {
                items.add(todo("AGREEMENT", "AGREEMENT", row.id(), row.projectId(), row.assignmentId(),
                        row.title() + " 正式约定等待确认", "WAITING", row.updatedAt(), row.revision(),
                        userId, "WAIT_ACK"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> changeProposalTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT pr.id, pr.task_id, pr.affected_users_json, pr.initiator_id, pr.revision,
                       pr.updated_at, t.title, p.id AS project_id, p.assignment_id,
                       EXISTS (SELECT 1 FROM proposal_response r
                               WHERE r.proposal_id = pr.id AND r.user_id = ?
                                 AND r.kind = 'CONFIRMATION' AND r.decision = 'ACCEPT') AS accepted
                FROM unit_change_proposal pr
                JOIN task t ON t.id = pr.task_id
                JOIN project p ON p.id = t.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE pr.status = 'PENDING'
                """, (rs, n) -> new ProposalSource(rs.getString("id"), rs.getString("task_id"),
                rs.getString("title"), rs.getString("project_id"), rs.getString("assignment_id"),
                rs.getString("initiator_id"), rs.getString("affected_users_json"),
                rs.getBoolean("accepted"), rs.getInt("revision"), instant(rs, "updated_at")),
                userId, userId)) {
            boolean affected = readStrings(row.affectedJson()).contains(userId);
            if (affected && !row.accepted()) {
                items.add(todo("CHANGE_PROPOSAL", "UNIT_CHANGE_PROPOSAL", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 分项调整待确认", "ACTION",
                        row.updatedAt(), row.revision(), userId, "CONFIRM"));
            } else if (userId.equals(row.initiatorId())) {
                items.add(todo("CHANGE_PROPOSAL", "UNIT_CHANGE_PROPOSAL", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 分项调整等待确认", "WAITING",
                        row.updatedAt(), row.revision(), userId, "WAIT_CONFIRM"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> reviewerInvitationTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT ri.id, ri.recipient_id, ri.revision, ri.created_at AS updated_at,
                       t.id AS task_id, t.title, p.id AS project_id, p.assignment_id, p.owner_id
                FROM reviewer_invitation ri
                JOIN task t ON t.id = ri.task_id
                JOIN project p ON p.id = t.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE ri.status = 'PENDING' AND ri.expires_at > ?
                  AND (ri.recipient_id = ? OR p.owner_id = ?)
                """, (rs, n) -> new ReviewerSource(rs.getString("id"), rs.getString("task_id"),
                rs.getString("title"), rs.getString("project_id"), rs.getString("assignment_id"),
                rs.getString("owner_id"), rs.getString("recipient_id"), rs.getInt("revision"),
                instant(rs, "updated_at")), userId, at(clock.instant()), userId, userId)) {
            if (userId.equals(row.recipientId())) {
                items.add(todo("REVIEWER_INVITATION", "REVIEWER_INVITATION", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 验收邀请待回应", "ACTION",
                        row.updatedAt(), row.revision(), userId, "RESPOND"));
            } else if (userId.equals(row.ownerId())) {
                items.add(todo("REVIEWER_INVITATION", "REVIEWER_INVITATION", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 验收邀请等待回应", "WAITING",
                        row.updatedAt(), row.revision(), userId, "WAIT_RESPOND"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> applicationTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        // 申请人等待处理，不要求其当前仍是项目成员。
        for (var row : jdbc.query("""
                SELECT a.id, a.applicant_id, a.revision, a.created_at, p.id AS project_id,
                       p.assignment_id, p.owner_id, COALESCE(p.name, '未命名项目') AS project_name
                FROM team_application a
                JOIN project p ON p.id = a.project_id
                WHERE a.status = 'PENDING' AND a.applicant_id = ?
                """, (rs, n) -> new ApplicationSource(rs.getString("id"), rs.getString("applicant_id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("owner_id"),
                rs.getString("project_name"), rs.getInt("revision"), instant(rs, "created_at")),
                userId)) {
            items.add(todo("APPLICATION", "TEAM_APPLICATION", row.id(), row.projectId(),
                    row.assignmentId(), row.projectName() + " 入组申请等待处理", "WAITING",
                    row.updatedAt(), row.revision(), userId, "WAIT_REVIEW"));
        }
        // 当前项目负责人处理申请。
        for (var row : jdbc.query("""
                SELECT a.id, a.applicant_id, a.revision, a.created_at, p.id AS project_id,
                       p.assignment_id, p.owner_id, COALESCE(p.name, '未命名项目') AS project_name
                FROM team_application a
                JOIN project p ON p.id = a.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE a.status = 'PENDING' AND p.owner_id = ?
                """, (rs, n) -> new ApplicationSource(rs.getString("id"), rs.getString("applicant_id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("owner_id"),
                rs.getString("project_name"), rs.getInt("revision"), instant(rs, "created_at")),
                userId, userId)) {
            items.add(todo("APPLICATION", "TEAM_APPLICATION", row.id(), row.projectId(),
                    row.assignmentId(), row.projectName() + " 入组申请待处理", "ACTION",
                    row.updatedAt(), row.revision(), userId, "REVIEW"));
        }
        return items;
    }

    private List<TeamViews.TodoView> invitationTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        // 受邀人等待回应，被邀请时通常还不是项目成员。
        for (var row : jdbc.query("""
                SELECT i.id, i.recipient_id, i.revision, i.created_at, p.id AS project_id,
                       p.assignment_id, p.owner_id, COALESCE(p.name, '未命名项目') AS project_name
                FROM team_invitation i
                JOIN project p ON p.id = i.project_id
                WHERE i.status = 'PENDING' AND i.expires_at > ? AND i.recipient_id = ?
                """, (rs, n) -> new ApplicationSource(rs.getString("id"), rs.getString("recipient_id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("owner_id"),
                rs.getString("project_name"), rs.getInt("revision"), instant(rs, "created_at")),
                at(clock.instant()), userId)) {
            items.add(todo("INVITATION", "TEAM_INVITATION", row.id(), row.projectId(),
                    row.assignmentId(), row.projectName() + " 入组邀请待回应", "ACTION",
                    row.updatedAt(), row.revision(), userId, "RESPOND"));
        }
        // 负责人等待受邀人回应。
        for (var row : jdbc.query("""
                SELECT i.id, i.recipient_id, i.revision, i.created_at, p.id AS project_id,
                       p.assignment_id, p.owner_id, COALESCE(p.name, '未命名项目') AS project_name
                FROM team_invitation i
                JOIN project p ON p.id = i.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE i.status = 'PENDING' AND i.expires_at > ? AND p.owner_id = ?
                """, (rs, n) -> new ApplicationSource(rs.getString("id"), rs.getString("recipient_id"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getString("owner_id"),
                rs.getString("project_name"), rs.getInt("revision"), instant(rs, "created_at")),
                userId, at(clock.instant()), userId)) {
            items.add(todo("INVITATION", "TEAM_INVITATION", row.id(), row.projectId(),
                    row.assignmentId(), row.projectName() + " 入组邀请等待回应", "WAITING",
                    row.updatedAt(), row.revision(), userId, "WAIT_RESPOND"));
        }
        return items;
    }

    private List<TeamViews.TodoView> roleTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT rn.id, rn.kind, rn.initiator_id, rn.revision, rn.updated_at, rn.plan_json::text AS plan_json,
                       p.id AS project_id, p.assignment_id, p.owner_id,
                       EXISTS (SELECT 1 FROM role_ack a WHERE a.negotiation_id = rn.id AND a.user_id = ?) AS acked,
                       EXISTS (SELECT 1 FROM jsonb_array_elements(rn.plan_json -> 'parties') e
                               WHERE e ->> 'userId' = ?) AS participant
                FROM role_negotiation rn
                JOIN project p ON p.id = rn.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE rn.status = 'PENDING'
                """, (rs, n) -> new RoleSource(rs.getString("id"), rs.getString("kind"),
                rs.getString("initiator_id"), rs.getString("project_id"), rs.getString("assignment_id"),
                rs.getString("owner_id"), rs.getBoolean("acked"), rs.getBoolean("participant"),
                rs.getInt("revision"), instant(rs, "updated_at")), userId, userId, userId)) {
            String type = switch (row.kind()) {
                case "PLAN" -> "ROLE_PLAN";
                case "SWAP" -> "ROLE_SWAP";
                default -> "ROLE_PROPOSAL";
            };
            String title = switch (row.kind()) {
                case "PLAN" -> "岗位调整方案";
                case "SWAP" -> "岗位互换提议";
                default -> "岗位提议";
            };
            if (row.participant() && !row.acked()) {
                items.add(todo(type, "ROLE_NEGOTIATION", row.id(), row.projectId(), row.assignmentId(),
                        title + "待回应", "ACTION", row.updatedAt(), row.revision(), userId, "RESPOND"));
            } else if (userId.equals(row.initiatorId()) || userId.equals(row.ownerId())) {
                items.add(todo(type, "ROLE_NEGOTIATION", row.id(), row.projectId(), row.assignmentId(),
                        title + "等待回应", "WAITING", row.updatedAt(), row.revision(), userId, "WAIT_RESPOND"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> removalVoteTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT v.id, v.status, v.target_user_id, v.initiator_id, v.statement,
                       v.electorate_json::text AS electorate_json, v.revision, v.updated_at,
                       p.id AS project_id, p.assignment_id,
                       EXISTS (SELECT 1 FROM removal_ballot b WHERE b.vote_id = v.id AND b.user_id = ?) AS voted
                FROM removal_vote v
                JOIN project p ON p.id = v.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE v.status IN ('STATEMENT', 'VOTING')
                """, (rs, n) -> new VoteSource(rs.getString("id"), rs.getString("status"),
                rs.getString("target_user_id"), rs.getString("initiator_id"), rs.getString("statement"),
                rs.getString("electorate_json"), rs.getString("project_id"), rs.getString("assignment_id"),
                rs.getBoolean("voted"), rs.getInt("revision"), instant(rs, "updated_at")),
                userId, userId)) {
            boolean electorate = readStrings(row.electorateJson()).contains(userId);
            if ("STATEMENT".equals(row.status()) && userId.equals(row.targetUserId())
                    && (row.statement() == null || row.statement().isBlank())) {
                items.add(todo("REMOVAL_VOTE", "REMOVAL_VOTE", row.id(), row.projectId(),
                        row.assignmentId(), "移出表决：本人陈述待提交", "ACTION", row.updatedAt(),
                        row.revision(), userId, "STATEMENT"));
            } else if ("VOTING".equals(row.status()) && electorate && !row.voted()) {
                items.add(todo("REMOVAL_VOTE", "REMOVAL_VOTE", row.id(), row.projectId(),
                        row.assignmentId(), "移出表决待投票", "ACTION", row.updatedAt(),
                        row.revision(), userId, "BALLOT"));
            } else if (userId.equals(row.targetUserId())) {
                items.add(todo("REMOVAL_VOTE", "REMOVAL_VOTE", row.id(), row.projectId(),
                        row.assignmentId(), "移出表决等待结果", "WAITING", row.updatedAt(),
                        row.revision(), userId, "WAIT_RESULT"));
            } else if (userId.equals(row.initiatorId())) {
                items.add(todo("REMOVAL_VOTE", "REMOVAL_VOTE", row.id(), row.projectId(),
                        row.assignmentId(), "移出表决等待投票结果", "WAITING", row.updatedAt(),
                        row.revision(), userId, "WAIT_VOTE"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> memberChangeTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT mc.id, mc.status, mc.target_user_id, mc.initiator_id, mc.kind,
                       mc.target_invite_status, mc.plan_version, mc.revision, mc.updated_at,
                       p.id AS project_id, p.assignment_id, p.owner_id, p.square_id,
                       c.id AS case_id, c.assignee_id AS case_assignee, c.status AS case_status,
                       (EXISTS (SELECT 1 FROM teacher_assignment ta
                                JOIN app_user tu ON tu.id = ta.user_id
                                JOIN square s ON s.id = ta.square_id
                                WHERE ta.square_id = p.square_id AND ta.user_id = ? AND ta.active = true
                                  AND tu.disabled = false AND tu.identity_status = 'VERIFIED'
                                  AND tu.school_id = s.school_id
                                  AND EXISTS (SELECT 1 FROM user_role r
                                              WHERE r.user_id = ta.user_id AND r.role = 'TEACHER'))
                        OR EXISTS (SELECT 1 FROM personnel_case_grant g
                                   JOIN app_user gu ON gu.id = g.user_id
                                   JOIN square gs ON gs.id = p.square_id
                                   WHERE c.id IS NOT NULL AND g.case_id = c.id AND g.user_id = ?
                                     AND g.status = 'ACTIVE'
                                     AND (g.expires_at IS NULL OR g.expires_at > ?)
                                     AND gu.disabled = false AND gu.identity_status = 'VERIFIED'
                                     AND gu.school_id = gs.school_id
                                     AND EXISTS (SELECT 1 FROM user_role gr
                                                 WHERE gr.user_id = g.user_id
                                                   AND gr.role = 'TEACHER'))) AS teacher,
                       EXISTS (SELECT 1 FROM membership m
                               WHERE m.project_id = p.id AND m.user_id = ?) AS recused,
                       (SELECT count(*) FROM handover_item hi
                        WHERE hi.change_id = mc.id AND hi.plan_version = mc.plan_version
                          AND hi.recipient_id = ?
                          AND NOT EXISTS (SELECT 1 FROM handover_ack ha
                                          WHERE ha.change_id = hi.change_id AND ha.plan_version = hi.plan_version
                                            AND ha.item_id = hi.id AND ha.user_id = hi.recipient_id
                                            AND ha.decision = 'ACCEPT')) AS pending_handover
                FROM member_change mc
                JOIN project p ON p.id = mc.project_id
                LEFT JOIN LATERAL (SELECT c2.id, c2.assignee_id, c2.status
                                   FROM personnel_case c2
                                   WHERE c2.member_change_id = mc.id
                                   ORDER BY c2.created_at, c2.id
                                   LIMIT 1) c ON true
                WHERE mc.status IN ('WAIT_PERSONNEL', 'RETURNED', 'READY')
                """, (rs, n) -> new MemberChangeSource(rs.getString("id"), rs.getString("status"),
                rs.getString("target_user_id"), rs.getString("initiator_id"), rs.getString("kind"),
                rs.getString("target_invite_status"), rs.getString("project_id"),
                rs.getString("assignment_id"), rs.getString("owner_id"), rs.getBoolean("teacher"),
                rs.getBoolean("recused"), rs.getString("case_id"), rs.getString("case_assignee"),
                rs.getString("case_status"), rs.getInt("pending_handover"), rs.getInt("revision"),
                instant(rs, "updated_at")),
                userId, userId, at(clock.instant()), userId, userId)) {
            boolean caseOpen = row.caseId() != null && !"CLOSED".equals(row.caseStatus());
            boolean currentHandler = caseOpen && userId.equals(row.caseAssignee()) && row.teacher() && !row.recused();
            boolean canAcceptCase = caseOpen && row.caseAssignee() == null && row.teacher() && !row.recused();
            if (row.pendingHandover() > 0) {
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "人员交接：本人接任事项待确认", "ACTION", row.updatedAt(),
                        row.revision(), userId, "HANDOVER"));
            } else if ("TRANSFER".equals(row.kind()) && userId.equals(row.targetUserId())
                    && "PENDING".equals(row.targetInviteStatus())) {
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "转组邀请待回应", "ACTION", row.updatedAt(),
                        row.revision(), userId, "TRANSFER"));
            } else if (currentHandler && ("WAIT_PERSONNEL".equals(row.status())
                    || "RETURNED".equals(row.status()))) {
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "人员事项待教师核对", "ACTION", row.updatedAt(),
                        row.revision(), userId, "PERSONNEL"));
            } else if (canAcceptCase && ("WAIT_PERSONNEL".equals(row.status())
                    || "RETURNED".equals(row.status()))) {
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "人员事项待受理", "ACTION", row.updatedAt(),
                        row.revision(), userId, "ACCEPT_CASE"));
            } else if (row.caseId() == null && row.teacher() && !row.recused()
                    && ("WAIT_PERSONNEL".equals(row.status()) || "RETURNED".equals(row.status()))) {
                // 兼容没有关联事项的历史数据；不把“任意教师”扩成新办理权限。
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "人员事项待教师核对", "ACTION", row.updatedAt(),
                        row.revision(), userId, "PERSONNEL"));
            } else if (userId.equals(row.initiatorId()) || userId.equals(row.ownerId())) {
                items.add(todo("MEMBER_CHANGE", "MEMBER_CHANGE", row.id(), row.projectId(),
                        row.assignmentId(), "人员变更等待处理", "WAITING", row.updatedAt(),
                        row.revision(), userId, "WAIT"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> materialRequestTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT mr.id, mr.status, mr.recipient_id, mr.revision, mr.updated_at,
                       c.id AS case_id, c.subject_user_id, c.title, c.project_id, p.assignment_id
                FROM material_request mr
                JOIN personnel_case c ON c.id = mr.case_id
                JOIN project p ON p.id = c.project_id
                JOIN membership m ON m.project_id = p.id AND m.user_id = ? AND m.status = 'ACTIVE'
                WHERE mr.status IN ('OPEN', 'RETURNED')
                """, (rs, n) -> new MaterialSource(rs.getString("id"), rs.getString("status"),
                rs.getString("recipient_id"), rs.getString("subject_user_id"), rs.getString("title"),
                rs.getString("project_id"), rs.getString("assignment_id"), rs.getInt("revision"),
                instant(rs, "updated_at")), userId)) {
            if (userId.equals(row.recipientId())) {
                items.add(todo("MATERIAL_REQUEST", "MATERIAL_REQUEST", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 材料待提交", "ACTION", row.updatedAt(),
                        row.revision(), userId, "RESPOND"));
            } else if (userId.equals(row.subjectUserId())) {
                items.add(todo("MATERIAL_REQUEST", "MATERIAL_REQUEST", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 材料等待提交", "WAITING", row.updatedAt(),
                        row.revision(), userId, "WAIT_MATERIAL"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> personnelHandoffTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        Instant now = clock.instant();
        for (var row : jdbc.query("""
                SELECT h.id, h.to_user_id, h.from_user_id, h.revision, h.created_at,
                       h.source_case_revision, c.revision AS case_revision, c.assignee_id,
                       c.status AS case_status, c.title, c.project_id, p.assignment_id,
                       (h.source_case_revision = c.revision) AS source_current,
                       EXISTS (SELECT 1 FROM membership m
                               WHERE m.project_id = c.project_id AND m.user_id = ?) AS recused,
                       EXISTS (SELECT 1 FROM membership m
                               WHERE m.project_id = c.project_id AND m.user_id = h.from_user_id) AS from_recused,
                       EXISTS (SELECT 1 FROM app_user u
                               WHERE u.id = h.to_user_id AND u.disabled = false
                                 AND u.identity_status = 'VERIFIED'
                                 AND u.school_id = sq.school_id
                                 AND EXISTS (SELECT 1 FROM user_role r
                                             WHERE r.user_id = u.id AND r.role = 'TEACHER')
                                 AND (EXISTS (SELECT 1 FROM teacher_assignment ta
                                              WHERE ta.square_id = c.square_id AND ta.user_id = u.id
                                                AND ta.active = true)
                                      OR EXISTS (SELECT 1 FROM personnel_case_grant g
                                                 WHERE g.case_id = c.id AND g.user_id = u.id
                                                   AND g.status = 'ACTIVE'
                                                   AND (g.expires_at IS NULL OR g.expires_at > ?)))) AS to_qualified,
                       EXISTS (SELECT 1 FROM app_user u
                               WHERE u.id = h.from_user_id AND u.disabled = false
                                 AND u.identity_status = 'VERIFIED'
                                 AND u.school_id = sq.school_id
                                 AND EXISTS (SELECT 1 FROM user_role r
                                             WHERE r.user_id = u.id AND r.role = 'TEACHER')
                                 AND (EXISTS (SELECT 1 FROM teacher_assignment ta
                                              WHERE ta.square_id = c.square_id AND ta.user_id = u.id
                                                AND ta.active = true)
                                      OR EXISTS (SELECT 1 FROM personnel_case_grant g
                                                 WHERE g.case_id = c.id AND g.user_id = u.id
                                                   AND g.status = 'ACTIVE'
                                                   AND (g.expires_at IS NULL OR g.expires_at > ?)))) AS from_qualified,
                       EXISTS (SELECT 1 FROM app_user u
                               WHERE u.id = h.to_user_id AND u.disabled = false
                                 AND u.identity_status = 'VERIFIED') AS to_active,
                       EXISTS (SELECT 1 FROM app_user u
                               WHERE u.id = h.from_user_id AND u.disabled = false
                                 AND u.identity_status = 'VERIFIED') AS from_active
                FROM personnel_handoff h
                JOIN personnel_case c ON c.id = h.case_id
                JOIN project p ON p.id = c.project_id
                JOIN square sq ON sq.id = c.square_id
                WHERE h.status = 'PENDING' AND (h.to_user_id = ? OR h.from_user_id = ?)
                """, (rs, n) -> new HandoffSource(rs.getString("id"), rs.getString("to_user_id"),
                rs.getString("from_user_id"), rs.getString("title"), rs.getString("project_id"),
                rs.getString("assignment_id"), rs.getInt("revision"), instant(rs, "created_at"),
                rs.getInt("source_case_revision"), rs.getInt("case_revision"),
                rs.getString("assignee_id"), rs.getString("case_status"),
                rs.getBoolean("source_current"), rs.getBoolean("to_qualified"),
                rs.getBoolean("from_qualified"), rs.getBoolean("to_active"),
                rs.getBoolean("from_active"), rs.getBoolean("recused"), rs.getBoolean("from_recused")),
                userId, at(now), at(now), userId, userId)) {
            boolean sourceValid = row.sourceCurrent() && !"CLOSED".equals(row.caseStatus());
            boolean fromStillOwner = row.fromUserId().equals(row.assigneeId())
                    && row.fromQualified() && row.fromActive() && !row.fromRecused();
            if (userId.equals(row.toUserId()) && sourceValid && fromStillOwner
                    && row.toQualified() && row.toActive() && !row.recused()) {
                items.add(todo("PERSONNEL_HANDOFF", "PERSONNEL_HANDOFF", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 人员交接待接受", "ACTION", row.updatedAt(),
                        row.revision(), userId, "ACCEPT"));
            } else if (userId.equals(row.fromUserId()) && sourceValid && fromStillOwner
                    && row.toQualified() && row.toActive() && !row.fromRecused()) {
                items.add(todo("PERSONNEL_HANDOFF", "PERSONNEL_HANDOFF", row.id(), row.projectId(),
                        row.assignmentId(), row.title() + " 人员交接等待接受", "WAITING", row.updatedAt(),
                        row.revision(), userId, "WAIT_ACCEPT"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> squareMergeTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT sm.id, sm.status, sm.initiator_id, sm.electorates_json::text AS electorate_json,
                       sm.revision, sm.updated_at, sm.source_square_id, sm.target_square_id,
                       EXISTS (SELECT 1 FROM merge_ballot b WHERE b.proposal_id = sm.id
                               AND b.user_id = ?) AS voted,
                       EXISTS (SELECT 1 FROM teacher_assignment ta
                               WHERE ta.square_id IN (sm.source_square_id, sm.target_square_id)
                                 AND ta.user_id = ? AND ta.active = true) AS teacher
                FROM square_merge sm
                WHERE sm.status IN ('VOTING', 'WAIT_TEACHER', 'READY')
                """, (rs, n) -> new MergeSource(rs.getString("id"), rs.getString("status"),
                rs.getString("initiator_id"), rs.getString("electorate_json"),
                rs.getString("source_square_id"), rs.getString("target_square_id"),
                rs.getBoolean("voted"), rs.getBoolean("teacher"), rs.getInt("revision"),
                instant(rs, "updated_at")), userId, userId)) {
            boolean electorate = inElectorate(row.electorateJson(), userId);
            if ("VOTING".equals(row.status()) && electorate && !row.voted()) {
                items.add(todo("SQUARE_MERGE", "SQUARE_MERGE", row.id(), null, null,
                        "课程广场合并待投票", "ACTION", row.updatedAt(), row.revision(), userId, "BALLOT"));
            } else if (row.teacher() && "WAIT_TEACHER".equals(row.status())) {
                items.add(todo("SQUARE_MERGE", "SQUARE_MERGE", row.id(), null, null,
                        "课程广场合并待教师核对", "ACTION", row.updatedAt(), row.revision(), userId,
                        "FACT_CHECK"));
            } else if (userId.equals(row.initiatorId())) {
                items.add(todo("SQUARE_MERGE", "SQUARE_MERGE", row.id(), null, null,
                        "课程广场合并等待处理", "WAITING", row.updatedAt(), row.revision(), userId,
                        "WAIT_MERGE"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> peerReviewTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        Instant cutoff = clock.instant().minus(PEER_REVIEW_WINDOW);
        for (var row : jdbc.query("""
                SELECT p.id, COALESCE(p.name, '未命名项目') AS name, p.assignment_id, p.archived_at,
                       (SELECT count(DISTINCT other.user_id)
                        FROM membership other
                        WHERE other.project_id = p.id AND other.user_id <> ?
                          AND NOT EXISTS (SELECT 1 FROM peer_review pr
                                          WHERE pr.project_id = p.id AND pr.author_id = ?
                                            AND pr.target_user_id = other.user_id
                                            AND pr.status = 'ACTIVE')) AS missing
                FROM project p
                JOIN membership mine ON mine.project_id = p.id AND mine.user_id = ?
                WHERE p.status = 'ARCHIVED' AND p.archived_at > ?
                """, (rs, n) -> new PeerSource(rs.getString("id"), rs.getString("name"),
                rs.getString("assignment_id"), instant(rs, "archived_at"), rs.getInt("missing")),
                userId, userId, userId, at(cutoff))) {
            if (row.missing() > 0) {
                items.add(todo("PEER_REVIEW", "PROJECT", row.projectId(), row.projectId(),
                        row.assignmentId(), row.name() + " 还有 " + row.missing() + " 位成员待互评",
                        "ACTION", row.archivedAt(), 1, userId, "PEER_REVIEW"));
            }
        }
        return items;
    }

    private List<TeamViews.TodoView> gradeAppealTodos(String userId) {
        List<TeamViews.TodoView> items = new ArrayList<>();
        for (var row : jdbc.query("""
                SELECT ga.id, ga.status, ga.student_id, ga.assignment_id, ga.revision, ga.created_at,
                       a.square_id,
                       EXISTS (SELECT 1 FROM teacher_assignment ta
                               WHERE ta.square_id = a.square_id AND ta.user_id = ? AND ta.active = true) AS teacher
                FROM grade_appeal ga
                JOIN assignment a ON a.id = ga.assignment_id
                WHERE ga.status IN ('PENDING', 'REVIEWING', 'AWAITING_REVISED_PUBLICATION')
                """, (rs, n) -> new AppealSource(rs.getString("id"), rs.getString("status"),
                rs.getString("student_id"), rs.getString("assignment_id"), rs.getBoolean("teacher"),
                rs.getInt("revision"), instant(rs, "created_at")), userId)) {
            if (row.teacher() && !"AWAITING_REVISED_PUBLICATION".equals(row.status())) {
                items.add(todo("GRADE_APPEAL", "GRADE_APPEAL", row.id(), null, row.assignmentId(),
                        "成绩复核待处理", "ACTION", row.updatedAt(), row.revision(), userId, "REVIEW"));
            } else if (row.teacher() && "AWAITING_REVISED_PUBLICATION".equals(row.status())) {
                items.add(todo("GRADE_APPEAL", "GRADE_APPEAL", row.id(), null, row.assignmentId(),
                        "成绩复核待新发布", "ACTION", row.updatedAt(), row.revision(), userId, "PUBLISH"));
            } else if (userId.equals(row.studentId())) {
                items.add(todo("GRADE_APPEAL", "GRADE_APPEAL", row.id(), null, row.assignmentId(),
                        "成绩复核等待处理", "WAITING", row.updatedAt(), row.revision(), userId, "WAIT_APPEAL"));
            }
        }
        return items;
    }

    private TeamViews.TodoView todo(String type, String resourceType, String resourceId,
                                    String projectId, String assignmentId, String title, String state,
                                    Instant updatedAt, int revision, String userId, String action) {
        String id = "todo_" + Hashing.sha256Hex(type + "|" + resourceId + "|" + action + "|" + userId)
                .substring(0, 40);
        return new TeamViews.TodoView(id, type, resourceType, resourceId, projectId, assignmentId,
                title, state, updatedAt, revision);
    }

    private boolean inElectorate(String json, String userId) {
        try {
            Map<String, List<String>> sides = mapper.readValue(json, ELECTORATES);
            return sides.values().stream().anyMatch(list -> list != null && list.contains(userId));
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> readStrings(String json) {
        try {
            return mapper.readValue(json, STRINGS);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private record SourceTodo(String type, String resourceType, String resourceId, String projectId,
                              String assignmentId, String title, String state, Instant updatedAt,
                              int revision, String action) {
        TeamViews.TodoView view(String userId, String action) {
            String id = "todo_" + Hashing.sha256Hex(type + "|" + resourceId + "|" + action + "|" + userId)
                    .substring(0, 40);
            return new TeamViews.TodoView(id, type, resourceType, resourceId, projectId, assignmentId,
                    title, state, updatedAt, revision);
        }
    }

    private record AgreementSource(String id, String taskId, String title, String projectId,
                                   String assignmentId, String ownerId, boolean required, boolean acked,
                                   int revision, Instant updatedAt) {
    }

    private record ProposalSource(String id, String taskId, String title, String projectId,
                                  String assignmentId, String initiatorId, String affectedJson,
                                  boolean accepted, int revision, Instant updatedAt) {
    }

    private record ReviewerSource(String id, String taskId, String title, String projectId,
                                  String assignmentId, String ownerId, String recipientId,
                                  int revision, Instant updatedAt) {
    }

    private record ApplicationSource(String id, String applicantId, String projectId,
                                     String assignmentId, String ownerId, String projectName,
                                     int revision, Instant updatedAt) {
    }

    private record RoleSource(String id, String kind, String initiatorId, String projectId,
                              String assignmentId, String ownerId, boolean acked, boolean participant,
                              int revision, Instant updatedAt) {
    }

    private record VoteSource(String id, String status, String targetUserId, String initiatorId,
                              String statement, String electorateJson, String projectId,
                              String assignmentId, boolean voted, int revision, Instant updatedAt) {
    }

    private record MemberChangeSource(String id, String status, String targetUserId, String initiatorId,
                                      String kind, String targetInviteStatus, String projectId,
                                      String assignmentId, String ownerId, boolean teacher, boolean recused,
                                      String caseId, String caseAssignee, String caseStatus,
                                      int pendingHandover, int revision, Instant updatedAt) {
    }

    private record MaterialSource(String id, String status, String recipientId, String subjectUserId,
                                  String title, String projectId, String assignmentId,
                                  int revision, Instant updatedAt) {
    }

    private record HandoffSource(String id, String toUserId, String fromUserId, String title,
                                 String projectId, String assignmentId, int revision, Instant updatedAt,
                                 int sourceCaseRevision, int caseRevision, String assigneeId,
                                 String caseStatus, boolean sourceCurrent, boolean toQualified,
                                 boolean fromQualified, boolean toActive, boolean fromActive,
                                 boolean recused, boolean fromRecused) {
    }

    private record MergeSource(String id, String status, String initiatorId, String electorateJson,
                               String sourceSquareId, String targetSquareId, boolean voted,
                               boolean teacher, int revision, Instant updatedAt) {
    }

    private record PeerSource(String projectId, String name, String assignmentId,
                              Instant archivedAt, int missing) {
    }

    private record AppealSource(String id, String status, String studentId, String assignmentId,
                                boolean teacher, int revision, Instant updatedAt) {
    }
}
