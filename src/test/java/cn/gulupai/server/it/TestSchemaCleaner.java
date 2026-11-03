package cn.gulupai.server.it;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 集成测试清库。各用例仍提交自己的表清单，这里只保证外键顺序，并在删除帮助条目后
 * 把通用包版本收回空库基线，避免类之间留下包版本。
 *
 * <p>空库经 V024 后的通用包版本是 2。需要别的起点的测试在清理之后自己写版本。
 */
public final class TestSchemaCleaner {

    public static final int GENERAL_PACK_BASELINE = 2;

    /** 子表必须先于父表删除。父表出现在清单里时，漏写的子表也会先删。 */
    private static final String[][] CHILD_BEFORE_PARENT = {
            {"help_feedback", "help_article"},
            {"support_history", "support_ticket"},
            {"support_reply", "support_ticket"},
            {"support_ticket", "support_route"},
            {"report_handling", "content_report"},
            {"board_reply", "board_topic"},
            {"topic_follow", "board_topic"},
            {"file_reference", "stored_file"},
            {"file_authorization", "stored_file"},
            {"cleanup_plan", "stored_file"}
    };

    private TestSchemaCleaner() {
    }

    public static void deleteAll(JdbcTemplate jdbc, List<String> tables) {
        List<String> ordered = order(tables);
        for (String table : ordered) {
            jdbc.update("DELETE FROM " + table);
        }
        if (ordered.contains("help_article")) {
            jdbc.update("""
                    UPDATE help_content_pack
                    SET version = ?, updated_at = '2026-01-01T00:00:00Z'::timestamptz
                    WHERE id = 'GENERAL'
                    """, GENERAL_PACK_BASELINE);
        }
    }

    static List<String> order(List<String> tables) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        for (String table : tables) {
            nodes.add(requireName(table));
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String[] edge : CHILD_BEFORE_PARENT) {
                if (nodes.contains(edge[1]) && nodes.add(edge[0])) {
                    grew = true;
                }
            }
        }
        List<String> remaining = new ArrayList<>(nodes);
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String next = null;
            for (String candidate : remaining) {
                if (predecessorsDone(candidate, ordered)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("测试清理存在循环依赖: " + remaining);
            }
            ordered.add(next);
            remaining.remove(next);
        }
        return List.copyOf(ordered);
    }

    private static boolean predecessorsDone(String table, List<String> done) {
        for (String[] edge : CHILD_BEFORE_PARENT) {
            if (edge[1].equals(table) && !done.contains(edge[0])) {
                return false;
            }
        }
        return true;
    }

    private static String requireName(String table) {
        if (table == null || !table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("非法表名");
        }
        return table;
    }
}
