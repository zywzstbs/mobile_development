package cn.gulupai.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U01：材料状态矩阵只在锁后的服务端生效，can* 与写入口使用同一规则。
 * 前置状态尽量由真实 HTTP 操作产生；只有数据异常和并发锁顺序用 JDBC。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovMaterialScenarioSupport.TestClockConfig.class)
class GovU01MaterialStateIT extends GovMaterialScenarioSupport {

    @Test
    void u01AcceptedMaterialRejectsNewResponseAndWithdrawal() throws Exception {
        Scenario s = materialScenario("u01-a");
        respondOk(s.member(), s.materialId(), "第一版", 1, "u01-a-v1");
        int reviewRevision = materialRevision(s.materialId());
        reviewOk(s.teacher(), s.materialId(), 1, "ACCEPT", "已验收", reviewRevision, "u01-a-accept");
        int acceptedRevision = materialRevision(s.materialId());

        MvcResult respond = respond(s.member(), s.materialId(), "验收后重交", acceptedRevision, "u01-a-v2");
        assertError(respond, 409, "SOURCE_CHANGED");
        MvcResult withdraw = withdraw(s.member(), s.materialId(), acceptedRevision, "验收后撤回", "u01-a-withdraw");
        assertError(withdraw, 409, "SOURCE_CHANGED");

        assertThat(materialStatus(s.materialId())).isEqualTo("ACCEPTED");
        assertThat(materialResponseVersion(s.materialId())).isEqualTo(1);
        assertThat(responseCount(s.materialId())).isEqualTo(1);
        assertThat(reviewCount(s.materialId())).isEqualTo(1);
        assertThat(materialRevision(s.materialId())).isEqualTo(acceptedRevision);
        assertThat(responseWithdrawn(s.materialId(), 1)).isFalse();
    }

    @Test
    void u01ClosedCaseRejectsNewResponseAndWithdrawal() throws Exception {
        Scenario s = materialScenario("u01-c");
        respondOk(s.member(), s.materialId(), "结案前正文", 1, "u01-c-v1");
        closeCase(s.teacher(), s.caseId(), "u01-c-close");
        int materialRevision = materialRevision(s.materialId());
        int responses = responseCount(s.materialId());

        MvcResult respond = respond(s.member(), s.materialId(), "结案后重交", materialRevision, "u01-c-v2");
        assertError(respond, 409, "SOURCE_CHANGED");
        MvcResult withdraw = withdraw(s.member(), s.materialId(), materialRevision, "结案后撤回", "u01-c-withdraw");
        assertError(withdraw, 409, "SOURCE_CHANGED");
        MvcResult review = review(s.teacher(), s.materialId(), 1, "ACCEPT", "结案后审核",
                materialRevision, "u01-c-review");
        assertError(review, 409, "SOURCE_CHANGED");

        assertThat(caseStatus(s.caseId())).isEqualTo("CLOSED");
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(materialStatus(s.materialId())).isEqualTo("RESPONDED");
        assertThat(materialResponseVersion(s.materialId())).isEqualTo(1);
        assertThat(responseCount(s.materialId())).isEqualTo(responses);
        assertThat(materialRevision(s.materialId())).isEqualTo(materialRevision);
        assertThat(responseWithdrawn(s.materialId(), 1)).isFalse();
    }

    @Test
    void u01WithdrawnCurrentVersionCannotBeReviewed() throws Exception {
        Scenario s = materialScenario("u01-d");
        respondOk(s.member(), s.materialId(), "待撤回正文", 1, "u01-d-v1");
        withdrawOk(s.member(), s.materialId(), materialRevision(s.materialId()), "撤回", "u01-d-withdraw");
        int withdrawnRevision = materialRevision(s.materialId());

        MvcResult review = review(s.teacher(), s.materialId(), 1, "ACCEPT", "按已撤回版本验收",
                withdrawnRevision, "u01-d-review");
        assertError(review, 409, "SOURCE_CHANGED");
        assertThat(materialStatus(s.materialId())).isEqualTo("WITHDRAWN");
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(responseWithdrawn(s.materialId(), 1)).isTrue();
        assertThat(materialRevision(s.materialId())).isEqualTo(withdrawnRevision);
    }

    @Test
    void u01EveryMaterialStateMatchesCanFlagsAndNewWrites() throws Exception {
        Scenario s = materialScenario("u01-e");

        // OPEN：可提交首版，无正文可撤回，不可审核。
        assertFlags(s, true, false, false);
        assertError(withdraw(s.member(), s.materialId(), materialRevision(s.materialId()), "空版本撤回", "u01-e-w0"),
                409, "SOURCE_CHANGED");
        assertThat(responseCount(s.materialId())).isZero();

        respondOk(s.member(), s.materialId(), "v1", 1, "u01-e-v1");
        // RESPONDED：学生可补交/撤回，当前处理人可审核。
        assertFlags(s, true, true, true);
        reviewOk(s.teacher(), s.materialId(), 1, "RETURN", "补充说明", materialRevision(s.materialId()),
                "u01-e-return");
        // RETURNED：可重新提交/撤回，但不能再审核旧版本。
        assertFlags(s, true, true, false);
        assertError(review(s.teacher(), s.materialId(), 1, "ACCEPT", "退回后直接验收",
                materialRevision(s.materialId()), "u01-e-review-returned"), 409, "SOURCE_CHANGED");
        assertThat(reviewCount(s.materialId())).isEqualTo(1);

        respondOk(s.member(), s.materialId(), "v2", materialRevision(s.materialId()), "u01-e-v2");
        withdrawOk(s.member(), s.materialId(), materialRevision(s.materialId()), "撤回 v2", "u01-e-withdraw");
        // WITHDRAWN：可重新提交；重复撤回和审核已撤回版本都拒绝。
        assertFlags(s, true, false, false);
        assertError(withdraw(s.member(), s.materialId(), materialRevision(s.materialId()), "重复撤回",
                "u01-e-withdraw-again"), 409, "SOURCE_CHANGED");
        assertError(review(s.teacher(), s.materialId(), 2, "ACCEPT", "验收撤回版本",
                materialRevision(s.materialId()), "u01-e-review-withdrawn"), 409, "SOURCE_CHANGED");
        assertThat(responseWithdrawn(s.materialId(), 2)).isTrue();

        respondOk(s.member(), s.materialId(), "v3", materialRevision(s.materialId()), "u01-e-v3");
        reviewOk(s.teacher(), s.materialId(), 3, "ACCEPT", "最终验收", materialRevision(s.materialId()),
                "u01-e-accept");
        // ACCEPTED：三个 can* 均为 false，新写入口全部拒绝。
        assertFlags(s, false, false, false);
        int acceptedRevision = materialRevision(s.materialId());
        assertError(respond(s.member(), s.materialId(), "v4", acceptedRevision, "u01-e-v4"),
                409, "SOURCE_CHANGED");
        assertError(withdraw(s.member(), s.materialId(), acceptedRevision, "验收后撤回", "u01-e-w-accepted"),
                409, "SOURCE_CHANGED");
        assertError(review(s.teacher(), s.materialId(), 3, "ACCEPT", "重复验收", acceptedRevision,
                "u01-e-review-accepted"), 409, "SOURCE_CHANGED");
        assertThat(materialResponseVersion(s.materialId())).isEqualTo(3);
        assertThat(responseCount(s.materialId())).isEqualTo(3);
        assertThat(reviewCount(s.materialId())).isEqualTo(2);
        assertThat(materialRevision(s.materialId())).isEqualTo(acceptedRevision);
    }

    @Test
    void u01ReturnedMaterialCanStillBeWithdrawn() throws Exception {
        Scenario s = materialScenario("u01-returned-withdraw");
        respondOk(s.member(), s.materialId(), "退回正文", 1, "u01-rw-v1");
        reviewOk(s.teacher(), s.materialId(), 1, "RETURN", "请补充", materialRevision(s.materialId()),
                "u01-rw-return");
        withdrawOk(s.member(), s.materialId(), materialRevision(s.materialId()), "退回后撤回", "u01-rw-withdraw");
        assertThat(materialStatus(s.materialId())).isEqualTo("WITHDRAWN");
        assertThat(responseWithdrawn(s.materialId(), 1)).isTrue();
    }

    @Test
    void u01ReturnDecisionRequiresNonBlankReason() throws Exception {
        Scenario s = materialScenario("u01-reason");
        respondOk(s.member(), s.materialId(), "待退回正文", 1, "u01-reason-v1");
        int revision = materialRevision(s.materialId());

        assertError(review(s.teacher(), s.materialId(), 1, "RETURN", null, revision, "u01-reason-empty"),
                422, "VALIDATION_FAILED");
        assertError(review(s.teacher(), s.materialId(), 1, "RETURN", "   ", revision, "u01-reason-blank"),
                422, "VALIDATION_FAILED");
        assertThat(materialStatus(s.materialId())).isEqualTo("RESPONDED");
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(materialRevision(s.materialId())).isEqualTo(revision);
    }

    @Test
    void u01ResponseV2InvalidatesReviewOfV1() throws Exception {
        Scenario s = materialScenario("u01-f");
        respondOk(s.member(), s.materialId(), "v1", 1, "u01-f-v1");
        respondOk(s.member(), s.materialId(), "v2", materialRevision(s.materialId()), "u01-f-v2");

        MvcResult reviewV1 = review(s.teacher(), s.materialId(), 1, "ACCEPT", "按 v1 验收",
                materialRevision(s.materialId()), "u01-f-review-v1");
        assertError(reviewV1, 409, "SOURCE_CHANGED");
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(materialStatus(s.materialId())).isEqualTo("RESPONDED");

        reviewOk(s.teacher(), s.materialId(), 2, "ACCEPT", "按当前 v2 验收",
                materialRevision(s.materialId()), "u01-f-review-v2");
        assertThat(materialStatus(s.materialId())).isEqualTo("ACCEPTED");
        assertThat(reviewCount(s.materialId())).isEqualTo(1);
    }

    @Test
    void u01SameKeyReplayHasNoSecondEffectAndStillChecksCurrentState() throws Exception {
        Scenario s = materialScenario("u01-g");
        respondOk(s.member(), s.materialId(), "同键正文", 1, "u01-g-respond");
        int afterFirst = materialRevision(s.materialId());
        MvcResult replay = respondOk(s.member(), s.materialId(), "同键正文", 1, "u01-g-respond");
        assertThat(data(replay).get("responseVersion").asInt()).isEqualTo(1);
        assertThat(responseCount(s.materialId())).isEqualTo(1);
        assertThat(materialRevision(s.materialId())).isEqualTo(afterFirst);

        reviewOk(s.teacher(), s.materialId(), 1, "ACCEPT", "同键验收", afterFirst, "u01-g-review");
        int afterReview = materialRevision(s.materialId());
        reviewOk(s.teacher(), s.materialId(), 1, "ACCEPT", "同键验收", afterFirst, "u01-g-review");
        assertThat(reviewCount(s.materialId())).isEqualTo(1);
        assertThat(materialRevision(s.materialId())).isEqualTo(afterReview);

        Scenario w = materialScenario("u01-gw");
        respondOk(w.member(), w.materialId(), "撤回正文", 1, "u01-gw-v1");
        withdrawOk(w.member(), w.materialId(), materialRevision(w.materialId()), "撤回", "u01-gw-withdraw");
        int afterWithdraw = materialRevision(w.materialId());
        withdrawOk(w.member(), w.materialId(), afterWithdraw - 1, "撤回", "u01-gw-withdraw");
        assertThat(responseWithdrawn(w.materialId(), 1)).isTrue();
        assertThat(responseCount(w.materialId())).isEqualTo(1);
        assertThat(materialRevision(w.materialId())).isEqualTo(afterWithdraw);
    }

    @Test
    void u01KeyReuseUnauthorizedAndStaleRevisionWriteNothing() throws Exception {
        Scenario s = materialScenario("u01-h");
        respondOk(s.member(), s.materialId(), "原始正文", 1, "u01-h-respond");
        int revision = materialRevision(s.materialId());
        String assistant = login("assistant_demo_01", "demo-password-04");

        // 同键异体：在业务动作前按请求摘要拒绝。
        assertError(respond(s.member(), s.materialId(), "同键不同正文", revision, "u01-h-respond"),
                409, "IDEMPOTENCY_KEY_REUSED");
        // 无权人：保留 403，不泄漏材料状态，也不写入。
        assertError(respond(assistant, s.materialId(), "无权提交", revision, "u01-h-unauthorized"),
                403, "FORBIDDEN");
        assertError(withdraw(assistant, s.materialId(), revision, "无权撤回", "u01-h-unauthorized-w"),
                403, "FORBIDDEN");
        // 旧 revision：既有版本冲突协议。
        assertError(respond(s.member(), s.materialId(), "旧版本提交", revision - 1, "u01-h-stale"),
                409, "RESOURCE_CHANGED");
        assertError(review(s.teacher(), s.materialId(), 1, "ACCEPT", "旧版本审核", revision - 1,
                "u01-h-stale-review"), 409, "RESOURCE_CHANGED");

        assertThat(materialStatus(s.materialId())).isEqualTo("RESPONDED");
        assertThat(materialResponseVersion(s.materialId())).isEqualTo(1);
        assertThat(responseCount(s.materialId())).isEqualTo(1);
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(materialRevision(s.materialId())).isEqualTo(revision);
    }

    @Test
    void u01EmptyOrMissingCurrentVersionCannotBeWithdrawnOrReviewed() throws Exception {
        Scenario s = materialScenario("u01-i");
        assertError(withdraw(s.member(), s.materialId(), materialRevision(s.materialId()), "空版本撤回",
                "u01-i-empty"), 409, "SOURCE_CHANGED");
        assertThat(responseCount(s.materialId())).isZero();
        assertThat(materialStatus(s.materialId())).isEqualTo("OPEN");
        assertThat(materialResponseVersion(s.materialId())).isZero();

        respondOk(s.member(), s.materialId(), "v1", 1, "u01-i-v1");
        int revision = materialRevision(s.materialId());
        // 数据异常：当前版本行缺失，不能伪造撤回或验收成功。
        jdbc.update("DELETE FROM material_response WHERE material_id = ? AND version = 1", s.materialId());
        assertError(withdraw(s.member(), s.materialId(), revision, "缺行撤回", "u01-i-missing-withdraw"),
                409, "SOURCE_CHANGED");
        assertError(review(s.teacher(), s.materialId(), 1, "ACCEPT", "缺行验收", revision,
                "u01-i-missing-review"), 409, "SOURCE_CHANGED");
        assertThat(materialStatus(s.materialId())).isEqualTo("RESPONDED");
        assertThat(reviewCount(s.materialId())).isZero();
        assertThat(materialRevision(s.materialId())).isEqualTo(revision);

        // 数据异常：材料仍是 RESPONDED，但当前版本已标记撤回；审核必须以真实行状态为准。
        Scenario anomaly = materialScenario("u01-i2");
        respondOk(anomaly.member(), anomaly.materialId(), "异常正文", 1, "u01-i2-v1");
        jdbc.update("UPDATE material_response SET withdrawn = true WHERE material_id = ? AND version = 1",
                anomaly.materialId());
        assertError(review(anomaly.teacher(), anomaly.materialId(), 1, "ACCEPT", "审核撤回行",
                materialRevision(anomaly.materialId()), "u01-i2-review"), 409, "SOURCE_CHANGED");
        assertThat(reviewCount(anomaly.materialId())).isZero();
        assertThat(materialStatus(anomaly.materialId())).isEqualTo("RESPONDED");
    }

    // ---- U01-J～L：两个真实 PG 连接控制锁顺序，竞争方必须是 HTTP 请求 ----

    @Test
    void u01ConcurrentReviewAndWithdrawOnlyOneTransitionWins() throws Exception {
        // 先撤回成功：后到的审核必须按当前状态拒绝，且不写 material_review。
        Scenario withdrawFirst = materialScenario("u01-j-w");
        respondOk(withdrawFirst.member(), withdrawFirst.materialId(), "v1", 1, "u01-jw-v1");
        int revision = materialRevision(withdrawFirst.materialId());
        try (Connection holder = lockCaseAndMaterial(withdrawFirst.caseId(), withdrawFirst.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> review = pool.submit(() -> review(withdrawFirst.teacher(),
                        withdrawFirst.materialId(), 1, "ACCEPT", "并发审核",
                        revision + 1, "u01-jw-review"));
                awaitLockOnCase();
                holder.createStatement().executeUpdate(
                        "UPDATE material_response SET withdrawn = true WHERE material_id = '"
                                + withdrawFirst.materialId() + "' AND version = 1 AND withdrawn = false");
                holder.createStatement().executeUpdate(
                        "UPDATE material_request SET status = 'WITHDRAWN', revision = revision + 1, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE id = '" + withdrawFirst.materialId()
                                + "' AND revision = " + revision);
                holder.commit();
                assertError(review.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(materialStatus(withdrawFirst.materialId())).isEqualTo("WITHDRAWN");
        assertThat(responseWithdrawn(withdrawFirst.materialId(), 1)).isTrue();
        assertThat(reviewCount(withdrawFirst.materialId())).isZero();
        assertThat(materialRevision(withdrawFirst.materialId())).isEqualTo(revision + 1);

        // 先审核成功：后到的撤回必须拒绝，不能出现 ACCEPTED 且 withdrawn=true。
        Scenario reviewFirst = materialScenario("u01-j-r");
        respondOk(reviewFirst.member(), reviewFirst.materialId(), "v1", 1, "u01-jr-v1");
        int reviewRevision = materialRevision(reviewFirst.materialId());
        try (Connection holder = lockCaseAndMaterial(reviewFirst.caseId(), reviewFirst.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> withdraw = pool.submit(() -> withdraw(reviewFirst.member(),
                        reviewFirst.materialId(), reviewRevision + 1, "并发撤回", "u01-jr-withdraw"));
                awaitLockOnCase();
                holder.createStatement().executeUpdate(
                        "INSERT INTO material_review (id, material_id, response_version, reviewer_id, decision, "
                                + "reason, created_at) VALUES ('" + UUID.randomUUID() + "', '"
                                + reviewFirst.materialId() + "', 1, '" + TEACHER_A
                                + "', 'ACCEPT', '并发审核', CURRENT_TIMESTAMP)");
                holder.createStatement().executeUpdate(
                        "UPDATE material_request SET status = 'ACCEPTED', revision = revision + 1, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE id = '" + reviewFirst.materialId()
                                + "' AND revision = " + reviewRevision);
                holder.commit();
                assertError(withdraw.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(materialStatus(reviewFirst.materialId())).isEqualTo("ACCEPTED");
        assertThat(responseWithdrawn(reviewFirst.materialId(), 1)).isFalse();
        assertThat(reviewCount(reviewFirst.materialId())).isEqualTo(1);
        assertThat(materialRevision(reviewFirst.materialId())).isEqualTo(reviewRevision + 1);
    }

    @Test
    void u01ConcurrentCloseWinsAgainstResponseAndWithdrawal() throws Exception {
        Scenario respondCase = materialScenario("u01-k-r");
        int respondRevision = materialRevision(respondCase.materialId());
        try (Connection holder = lockCaseAndMaterial(respondCase.caseId(), respondCase.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> respond = pool.submit(() -> respond(respondCase.member(),
                        respondCase.materialId(), "结案并发提交", respondRevision, "u01-kr-respond"));
                awaitLockOnCase();
                closeCaseRaw(holder, respondCase.caseId());
                holder.commit();
                assertError(respond.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(caseStatus(respondCase.caseId())).isEqualTo("CLOSED");
        assertThat(responseCount(respondCase.materialId())).isZero();
        assertThat(materialStatus(respondCase.materialId())).isEqualTo("OPEN");

        Scenario withdrawCase = materialScenario("u01-k-w");
        respondOk(withdrawCase.member(), withdrawCase.materialId(), "结案前正文", 1, "u01-kw-v1");
        int withdrawRevision = materialRevision(withdrawCase.materialId());
        try (Connection holder = lockCaseAndMaterial(withdrawCase.caseId(), withdrawCase.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> withdraw = pool.submit(() -> withdraw(withdrawCase.member(),
                        withdrawCase.materialId(), withdrawRevision, "结案并发撤回", "u01-kw-withdraw"));
                awaitLockOnCase();
                closeCaseRaw(holder, withdrawCase.caseId());
                holder.commit();
                assertError(withdraw.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(caseStatus(withdrawCase.caseId())).isEqualTo("CLOSED");
        assertThat(materialStatus(withdrawCase.materialId())).isEqualTo("RESPONDED");
        assertThat(responseWithdrawn(withdrawCase.materialId(), 1)).isFalse();
        assertThat(materialRevision(withdrawCase.materialId())).isEqualTo(withdrawRevision);
    }

    @Test
    void u01ConcurrentReviewAndResponseV2RespectVersionOrder() throws Exception {
        // 审核先成功：新补交被 ACCEPTED 状态拒绝。
        Scenario reviewFirst = materialScenario("u01-l-r");
        respondOk(reviewFirst.member(), reviewFirst.materialId(), "v1", 1, "u01-lr-v1");
        int revision = materialRevision(reviewFirst.materialId());
        try (Connection holder = lockCaseAndMaterial(reviewFirst.caseId(), reviewFirst.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> respondV2 = pool.submit(() -> respond(reviewFirst.member(),
                        reviewFirst.materialId(), "审核后补交", revision + 1, "u01-lr-v2"));
                awaitLockOnCase();
                holder.createStatement().executeUpdate(
                        "INSERT INTO material_review (id, material_id, response_version, reviewer_id, decision, "
                                + "reason, created_at) VALUES ('" + UUID.randomUUID() + "', '"
                                + reviewFirst.materialId() + "', 1, '" + TEACHER_A
                                + "', 'ACCEPT', '并发审核', CURRENT_TIMESTAMP)");
                holder.createStatement().executeUpdate(
                        "UPDATE material_request SET status = 'ACCEPTED', revision = revision + 1, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE id = '" + reviewFirst.materialId()
                                + "' AND revision = " + revision);
                holder.commit();
                assertError(respondV2.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(materialStatus(reviewFirst.materialId())).isEqualTo("ACCEPTED");
        assertThat(responseCount(reviewFirst.materialId())).isEqualTo(1);
        assertThat(materialResponseVersion(reviewFirst.materialId())).isEqualTo(1);

        // 补交 v2 先成功：按 v1 的审核必须在锁后报告版本冲突。
        Scenario respondFirst = materialScenario("u01-l-w");
        respondOk(respondFirst.member(), respondFirst.materialId(), "v1", 1, "u01-lw-v1");
        int current = materialRevision(respondFirst.materialId());
        try (Connection holder = lockCaseAndMaterial(respondFirst.caseId(), respondFirst.materialId())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> reviewV1 = pool.submit(() -> review(respondFirst.teacher(),
                        respondFirst.materialId(), 1, "ACCEPT", "按 v1 并发审核",
                        current + 1, "u01-lw-review"));
                awaitLockOnCase();
                holder.createStatement().executeUpdate(
                        "INSERT INTO material_response (id, material_id, version, author_id, content, "
                                + "evidence_json, withdrawn, created_at, created_at_confidence, time_source) "
                                + "VALUES ('" + UUID.randomUUID() + "', '"
                                + respondFirst.materialId() + "', 2, '" + MEMBER
                                + "', '并发 v2', '[]'::jsonb, false, CURRENT_TIMESTAMP, "
                                + "'EXACT', 'SERVER_SUBMISSION')");
                holder.createStatement().executeUpdate(
                        "UPDATE material_request SET status = 'RESPONDED', response_version = 2, "
                                + "response_json = '{\"content\":\"并发 v2\",\"version\":2}'::jsonb, "
                                + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = '"
                                + respondFirst.materialId() + "' AND revision = " + current);
                holder.commit();
                assertError(reviewV1.get(10, TimeUnit.SECONDS), 409, "SOURCE_CHANGED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(materialStatus(respondFirst.materialId())).isEqualTo("RESPONDED");
        assertThat(materialResponseVersion(respondFirst.materialId())).isEqualTo(2);
        assertThat(responseCount(respondFirst.materialId())).isEqualTo(2);
        assertThat(reviewCount(respondFirst.materialId())).isZero();
    }

    // ---- helpers ----

    private void assertFlags(Scenario scenario, boolean canRespond, boolean canWithdraw, boolean canReview)
            throws Exception {
        JsonNode student = materialData(scenario.member(), scenario.materialId());
        JsonNode teacher = materialData(scenario.teacher(), scenario.materialId());
        assertThat(student.get("canRespond").asBoolean()).isEqualTo(canRespond);
        assertThat(student.get("canWithdraw").asBoolean()).isEqualTo(canWithdraw);
        assertThat(teacher.get("canReview").asBoolean()).isEqualTo(canReview);
        assertThat(teacher.get("canRespond").asBoolean()).isFalse();
        assertThat(teacher.get("canWithdraw").asBoolean()).isFalse();
        assertThat(student.get("canReview").asBoolean()).isFalse();
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
    }

    private Connection lockCaseAndMaterial(String caseId, String materialId) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        connection.createStatement().execute("SET statement_timeout = '10s'");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM personnel_case WHERE id = ? FOR UPDATE")) {
            statement.setString(1, caseId);
            statement.executeQuery().close();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM material_request WHERE id = ? FOR UPDATE")) {
            statement.setString(1, materialId);
            statement.executeQuery().close();
        }
        return connection;
    }

    private void closeCaseRaw(Connection holder, String caseId) throws Exception {
        holder.createStatement().executeUpdate(
                "UPDATE personnel_case SET status = 'CLOSED', revision = revision + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = '" + caseId + "'");
    }

    private void awaitLockOnCase() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND state = 'active'
                      AND wait_event_type = 'Lock' AND query ILIKE '%personnel_case%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("竞争请求没有先等待 case 锁");
    }
}
