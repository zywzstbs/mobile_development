package cn.gulupai.server.it;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSchemaCleanerTest {

    /** V031 之后，清理 app_user / school 时必须连带清理的依赖表。 */
    private static final List<String> PLATFORM_AND_REGISTRATION_DEPENDENTS = List.of(
            "platform_admin_capability",
            "platform_admin_scope",
            "platform_admin_grant",
            "admin_reauthentication",
            "admin_operation",
            "registration_application");

    @Test
    void childrenAreDeletedBeforeParentsAndUnrelatedOrderStays() {
        List<String> ordered = TestSchemaCleaner.order(List.of(
                "help_article", "help_feedback", "support_route", "support_ticket",
                "board_topic", "board_reply", "school"));

        assertThat(index(ordered, "help_feedback")).isLessThan(index(ordered, "help_article"));
        assertThat(index(ordered, "support_history")).isLessThan(index(ordered, "support_ticket"));
        assertThat(index(ordered, "support_reply")).isLessThan(index(ordered, "support_ticket"));
        assertThat(index(ordered, "support_ticket")).isLessThan(index(ordered, "support_route"));
        assertThat(index(ordered, "board_reply")).isLessThan(index(ordered, "board_topic"));
        assertThat(index(ordered, "topic_follow")).isLessThan(index(ordered, "board_topic"));
        assertThat(ordered).contains("school");
    }

    @Test
    void registrationIsDeletedBeforeUserAndSchoolAndReceiptIsNotImplied() {
        List<String> ordered = TestSchemaCleaner.order(List.of("school", "app_user"));

        assertThat(ordered).containsExactlyInAnyOrderElementsOf(concat(
                PLATFORM_AND_REGISTRATION_DEPENDENTS, "app_user", "school"));
        // registration_receipt 没有外键，不能因为用户/学校被清理就自动带入。
        assertThat(ordered).doesNotContain("registration_receipt");

        assertBefore(ordered, "registration_application", "app_user");
        assertBefore(ordered, "registration_application", "school");
        assertBefore(ordered, "app_user", "school");
    }

    @Test
    void userOnlyCleanupPullsPlatformDependentsButNotReceiptOrSchool() {
        List<String> ordered = TestSchemaCleaner.order(List.of("app_user"));

        assertThat(ordered).containsExactlyInAnyOrderElementsOf(concat(
                PLATFORM_AND_REGISTRATION_DEPENDENTS, "app_user"));
        assertThat(ordered).doesNotContain("registration_receipt", "school", "auth_session");

        assertBefore(ordered, "platform_admin_capability", "platform_admin_grant");
        assertBefore(ordered, "platform_admin_scope", "platform_admin_grant");
        assertBefore(ordered, "platform_admin_grant", "app_user");
        assertBefore(ordered, "admin_reauthentication", "app_user");
        assertBefore(ordered, "admin_operation", "app_user");
        assertBefore(ordered, "registration_application", "app_user");
    }

    @Test
    void schoolOnlyCleanupPullsUserAndPlatformDependents() {
        List<String> ordered = TestSchemaCleaner.order(List.of("school"));

        assertThat(ordered).containsExactlyInAnyOrderElementsOf(concat(
                PLATFORM_AND_REGISTRATION_DEPENDENTS, "app_user", "school"));
        assertThat(ordered).doesNotContain("registration_receipt");

        assertBefore(ordered, "app_user", "school");
        assertBefore(ordered, "registration_application", "school");
        assertBefore(ordered, "platform_admin_scope", "school");
    }

    @Test
    void sessionCleanupPullsReauthenticationRecords() {
        List<String> ordered = TestSchemaCleaner.order(List.of("auth_session"));

        assertThat(ordered).containsExactly("admin_reauthentication", "auth_session");
        assertBefore(ordered, "admin_reauthentication", "auth_session");
    }

    @Test
    void tablesWithoutKnownDependentsKeepCallerOrder() {
        assertThat(TestSchemaCleaner.order(List.of("audit_event", "rate_window")))
                .containsExactly("audit_event", "rate_window");
        assertThat(TestSchemaCleaner.order(List.of("rate_window", "audit_event")))
                .containsExactly("rate_window", "audit_event");
    }

    private static List<String> concat(List<String> base, String... extra) {
        List<String> merged = new java.util.ArrayList<>(base);
        merged.addAll(List.of(extra));
        return merged;
    }

    private static void assertBefore(List<String> ordered, String child, String parent) {
        assertThat(index(ordered, child))
                .as("%s 必须先于 %s 删除", child, parent)
                .isLessThan(index(ordered, parent));
    }

    private static int index(List<String> ordered, String table) {
        int index = ordered.indexOf(table);
        assertThat(index).as("%s 应在清理清单中", table).isNotNegative();
        return index;
    }
}
