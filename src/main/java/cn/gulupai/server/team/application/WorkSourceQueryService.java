package cn.gulupai.server.team.application;

import cn.gulupai.server.team.api.TeamViews;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 作品来源证据的唯一只读口径，供 TEAM-06 列表与作品保存校验共用。
 *
 * <p>归属规则：
 * <ul>
 *   <li>正式任务：只认提交时固定的 {@code submission.agreement_version} 对应份额；
 *       旧版本份额不能分享新版本接任人的成果，已完成的历史提交仍按当时版本保留。</li>
 *   <li>普通任务：合法提交人 {@code submitted_by} 在 REVIEWED + PASSED 时取得整任务有效交付；
 *       普通任务没有 unit_share，不能机械套用正式分项。</li>
 *   <li>确认项、独立验收、准时/迟交均基于同一份证据 CTE；先分别去重聚合，再按项目合并。</li>
 * </ul>
 */
@Service
public class WorkSourceQueryService {

    private static final String EVIDENCE_CTE = """
            WITH target AS (SELECT ?::varchar AS uid),
            formal_delivery AS (
                SELECT t.project_id AS project_id, ua.unit_id AS evidence_id,
                       sub.id AS submission_id, sub.submitted_at,
                       COALESCE(NULLIF(sub.unit_due_snapshot_json ->> ua.unit_id, '')::timestamptz,
                                sub.due_snapshot) AS effective_due
                FROM unit_acceptance ua
                JOIN submission sub ON sub.id = ua.submission_id AND sub.status = 'REVIEWED'
                JOIN acceptance ac ON ac.id = ua.acceptance_id AND ac.submission_id = sub.id
                JOIN delivery_unit u ON u.id = ua.unit_id AND u.task_id = sub.task_id
                JOIN task t ON t.id = sub.task_id AND t.mode = 'ASSESSED'
                JOIN unit_share sh ON sh.unit_id = ua.unit_id
                     AND sh.agreement_version = sub.agreement_version
                     AND sh.user_id = (SELECT uid FROM target)
                WHERE ua.passed = true
                  AND u.id IN (SELECT jsonb_array_elements_text(sub.unit_ids_json))
            ),
            formal_confirmed AS (
                SELECT t.project_id, cc.unit_id
                FROM contribution_confirmation cc
                JOIN acceptance ac ON ac.id = cc.acceptance_id
                JOIN submission sub ON sub.id = cc.submission_id AND sub.id = ac.submission_id
                JOIN delivery_unit u ON u.id = cc.unit_id AND u.task_id = sub.task_id
                JOIN task t ON t.id = u.task_id AND t.mode = 'ASSESSED'
                JOIN unit_share sh ON sh.unit_id = cc.unit_id
                     AND sh.agreement_version = sub.agreement_version
                     AND sh.user_id = cc.user_id
                WHERE cc.user_id = (SELECT uid FROM target)
                  AND cc.unit_id IN (SELECT jsonb_array_elements_text(sub.unit_ids_json))
            ),
            formal_review AS (
                SELECT t.project_id, ac.id AS review_id
                FROM acceptance ac
                JOIN submission sub ON sub.id = ac.submission_id
                JOIN task t ON t.id = sub.task_id AND t.mode = 'ASSESSED'
                WHERE ac.reviewer_id = (SELECT uid FROM target)
                  AND ac.kind = 'INDEPENDENT'
                  AND NOT EXISTS (
                      SELECT 1 FROM unit_share sh
                      WHERE sh.user_id = ac.reviewer_id
                        AND sh.agreement_version = sub.agreement_version
                        AND sh.unit_id IN (SELECT jsonb_array_elements_text(sub.unit_ids_json)))
            ),
            ordinary_delivery AS (
                SELECT t.project_id, sub.id AS submission_id, sub.submitted_at,
                       sub.due_snapshot AS effective_due
                FROM submission sub
                JOIN task t ON t.id = sub.task_id AND t.mode = 'ORDINARY'
                JOIN acceptance ac ON ac.submission_id = sub.id AND ac.decision = 'PASSED'
                WHERE sub.status = 'REVIEWED'
                  AND sub.submitted_by = (SELECT uid FROM target)
            ),
            ordinary_review AS (
                SELECT t.project_id, ac.id AS review_id
                FROM acceptance ac
                JOIN submission sub ON sub.id = ac.submission_id
                JOIN task t ON t.id = sub.task_id AND t.mode = 'ORDINARY'
                WHERE ac.reviewer_id = (SELECT uid FROM target)
                  AND ac.kind = 'INDEPENDENT'
                  AND sub.submitted_by <> ac.reviewer_id
            ),
            candidate AS (
                SELECT project_id FROM formal_delivery
                UNION SELECT project_id FROM formal_confirmed
                UNION SELECT project_id FROM formal_review
                UNION SELECT project_id FROM ordinary_delivery
                UNION SELECT project_id FROM ordinary_review
            ),
            formal_accepted AS (
                SELECT project_id, count(DISTINCT evidence_id) AS n
                FROM formal_delivery GROUP BY project_id
            ),
            ordinary_accepted AS (
                SELECT project_id, count(DISTINCT submission_id) AS n
                FROM ordinary_delivery GROUP BY project_id
            ),
            confirmed AS (
                SELECT project_id, count(DISTINCT unit_id) AS n
                FROM formal_confirmed GROUP BY project_id
            ),
            reviews AS (
                SELECT project_id, count(DISTINCT review_id) AS n FROM (
                    SELECT project_id, review_id FROM formal_review
                    UNION ALL
                    SELECT project_id, review_id FROM ordinary_review) merged
                GROUP BY project_id
            ),
            deliveries AS (
                SELECT project_id, submitted_at, effective_due FROM formal_delivery
                UNION ALL
                SELECT project_id, submitted_at, effective_due FROM ordinary_delivery
            ),
            on_time AS (
                SELECT project_id, count(*) AS n FROM deliveries
                WHERE effective_due IS NOT NULL AND submitted_at <= effective_due
                GROUP BY project_id
            ),
            late AS (
                SELECT project_id, count(*) AS n FROM deliveries
                WHERE effective_due IS NOT NULL AND submitted_at > effective_due
                GROUP BY project_id
            )
            """;

    private final JdbcTemplate jdbc;

    public WorkSourceQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TeamViews.WorkSourceProjectView> sources(String userId) {
        return jdbc.query(EVIDENCE_CTE + """
                SELECT p.id,
                       COALESCE(p.name, '未命名项目') AS display_name,
                       COALESCE(fa.n, 0) + COALESCE(oa.n, 0) AS accepted_deliveries,
                       COALESCE(conf.n, 0) AS confirmed_units,
                       COALESCE(rev.n, 0) AS independent_reviews,
                       COALESCE(ot.n, 0) AS on_time_deliveries,
                       COALESCE(lt.n, 0) AS late_deliveries
                FROM candidate cand
                JOIN project p ON p.id = cand.project_id
                LEFT JOIN formal_accepted fa ON fa.project_id = p.id
                LEFT JOIN ordinary_accepted oa ON oa.project_id = p.id
                LEFT JOIN confirmed conf ON conf.project_id = p.id
                LEFT JOIN reviews rev ON rev.project_id = p.id
                LEFT JOIN on_time ot ON ot.project_id = p.id
                LEFT JOIN late lt ON lt.project_id = p.id
                ORDER BY p.updated_at DESC, p.id ASC
                """, (rs, n) -> {
                    String projectId = rs.getString("id");
                    return new TeamViews.WorkSourceProjectView(projectId,
                            rs.getString("display_name"), projectId,
                            new TeamViews.WorkEvidence(projectId,
                                    rs.getInt("accepted_deliveries"),
                                    rs.getInt("confirmed_units"),
                                    rs.getInt("independent_reviews"),
                                    rs.getInt("on_time_deliveries"),
                                    rs.getInt("late_deliveries")));
                }, userId);
    }

    /**
     * 作品保存与来源列表使用同一证据谓词。只有列表里会出现的本人证据项目才可按来源保存。
     */
    public boolean canReference(String userId, String projectId) {
        Boolean exists = jdbc.queryForObject(EVIDENCE_CTE + """
                SELECT EXISTS(SELECT 1 FROM candidate WHERE project_id = ?)
                """, Boolean.class, userId, projectId);
        return Boolean.TRUE.equals(exists);
    }
}
