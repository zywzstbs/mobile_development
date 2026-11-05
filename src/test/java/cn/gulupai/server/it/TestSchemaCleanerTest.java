package cn.gulupai.server.it;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSchemaCleanerTest {

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
        assertThat(TestSchemaCleaner.order(List.of("school", "app_user")))
                .containsExactly("registration_application", "app_user", "school");
        assertThat(TestSchemaCleaner.order(List.of("app_user")))
                .containsExactly("registration_application", "app_user");
        assertThat(TestSchemaCleaner.order(List.of("school")))
                .containsExactly("registration_application", "app_user", "school");
        assertThat(TestSchemaCleaner.order(List.of("audit_event", "rate_window")))
                .containsExactly("audit_event", "rate_window");
    }

    private static int index(List<String> ordered, String table) {
        int index = ordered.indexOf(table);
        assertThat(index).isNotNegative();
        return index;
    }
}
