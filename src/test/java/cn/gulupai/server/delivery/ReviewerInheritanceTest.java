package cn.gulupai.server.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewerInheritanceTest {

    @Test
    void targetPolicyDecidesEvenWhenAnotherReviewerExists() {
        ReviewerInheritance.Pass older = pass("task", "u1", 1, "r1", "SELF_CHECK", true);
        ReviewerInheritance.Pass otherKind = pass("task", "u1", 1, "r1", "INDEPENDENT", true);
        ReviewerInheritance.Pass otherUser = pass("task", "u1", 1, "r2", "SELF_CHECK", true);
        List<String> units = List.of("u1");

        assertThat(ReviewerInheritance.inherited(policy("r1", "SELF_CHECK"), 2, "task", units, List.of(older)))
                .isTrue();
        assertThat(ReviewerInheritance.inherited(policy("r1", "SELF_CHECK"), 2, "task", units, List.of(otherKind)))
                .isFalse();
        assertThat(ReviewerInheritance.inherited(policy("r1", "INDEPENDENT"), 2, "task", units, List.of(older)))
                .isFalse();
        assertThat(ReviewerInheritance.inherited(policy("r2", "INDEPENDENT"), 2, "task", units, List.of(older, otherUser)))
                .isFalse();
        assertThat(ReviewerInheritance.inherited(policy("r2", "SELF_CHECK"), 2, "task", units, List.of(otherUser)))
                .isTrue();
    }

    @Test
    void futureOtherTaskWithdrawnAndEmptyDoNotCount() {
        List<String> units = List.of("u1", "u2");
        ReviewerInheritance.Pass future = pass("task", "u1", 3, "r1", "SELF_CHECK", true);
        ReviewerInheritance.Pass sameVersion = pass("task", "u1", 2, "r1", "SELF_CHECK", true);
        ReviewerInheritance.Pass missingVersion = new ReviewerInheritance.Pass(
                "task", "u1", null, "r1", "SELF_CHECK", true, false);
        ReviewerInheritance.Pass otherTask = pass("other", "u1", 1, "r1", "SELF_CHECK", true);
        ReviewerInheritance.Pass otherUnit = pass("task", "u9", 1, "r1", "SELF_CHECK", true);
        ReviewerInheritance.Pass withdrawn = new ReviewerInheritance.Pass(
                "task", "u1", 1, "r1", "SELF_CHECK", true, true);
        ReviewerInheritance.Pass returned = pass("task", "u1", 1, "r1", "SELF_CHECK", false);

        assertThat(ReviewerInheritance.inherited(policy("r1", "SELF_CHECK"), 2, "task", units, List.of(
                future, sameVersion, missingVersion, otherTask, otherUnit, withdrawn, returned))).isFalse();
        assertThat(ReviewerInheritance.inherited(policy("r1", "SELF_CHECK"), 2, "task", units, List.of())).isFalse();
        assertThat(ReviewerInheritance.inherited(null, 2, "task", units, List.of(pass("task", "u1", 1, "r1", "SELF_CHECK", true))))
                .isFalse();
        assertThat(ReviewerInheritance.inherited(policy(" ", "SELF_CHECK"), 2, "task", units,
                List.of(pass("task", "u1", 1, "r1", "SELF_CHECK", true)))).isFalse();
    }

    @Test
    void oneMatchingUnitIsEnough() {
        ReviewerInheritance.Pass matched = pass("task", "u2", 1, "r1", "INDEPENDENT", true);
        ReviewerInheritance.Pass other = pass("task", "u1", 1, "r2", "SELF_CHECK", true);

        assertThat(ReviewerInheritance.inherited(policy("r1", "INDEPENDENT"), 4, "task", List.of("u1", "u2"),
                List.of(other, matched))).isTrue();
        assertThat(ReviewerInheritance.inherited(policy("r1", "INDEPENDENT"), 4, "task", List.of("u1"),
                List.of(matched))).isFalse();
    }

    private static ReviewerInheritance.Policy policy(String userId, String kind) {
        return new ReviewerInheritance.Policy(userId, kind);
    }

    private static ReviewerInheritance.Pass pass(String taskId, String unitId, int version, String reviewerId,
                                                String kind, boolean passed) {
        return new ReviewerInheritance.Pass(taskId, unitId, version, reviewerId, kind, passed, false);
    }
}
