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
    void listWithoutParentsIsUnchanged() {
        assertThat(TestSchemaCleaner.order(List.of("school", "app_user")))
                .containsExactly("school", "app_user");
    }

    private static int index(List<String> ordered, String table) {
        int index = ordered.indexOf(table);
        assertThat(index).isNotNegative();
        return index;
    }
}
