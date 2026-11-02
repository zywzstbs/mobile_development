package cn.gulupai.server.course.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CourseDictionaryRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<CourseNameRow> courseNameMapper = (rs, rowNum) -> new CourseNameRow(
            rs.getString("id"),
            rs.getString("school_id"),
            rs.getString("full_name"),
            rs.getString("normalized_name"),
            readAliases(rs.getString("aliases_json")));

    private final RowMapper<TermRow> termMapper = (rs, rowNum) -> new TermRow(
            rs.getString("id"),
            rs.getString("school_id"),
            rs.getString("name"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getInt("ordinal"));

    private final RowMapper<PeriodRow> periodMapper = (rs, rowNum) -> new PeriodRow(
            rs.getString("id"),
            rs.getString("school_id"),
            rs.getString("term_id"),
            rs.getString("label"),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            rs.getInt("ordinal"));

    public CourseDictionaryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<SchoolRow> findSchool(String schoolId) {
        return jdbcTemplate.query("SELECT id, name, timezone FROM school WHERE id = ?",
                        (rs, rowNum) -> new SchoolRow(rs.getString("id"), rs.getString("name"),
                                rs.getString("timezone")), schoolId)
                .stream().findFirst();
    }

    public Optional<TermRow> findTerm(String termId) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, name, start_date, end_date, ordinal
                        FROM term WHERE id = ?
                        """, termMapper, termId)
                .stream().findFirst();
    }

    public List<TermRow> listTerms(String schoolId) {
        return jdbcTemplate.query("""
                SELECT id, school_id, name, start_date, end_date, ordinal
                FROM term WHERE school_id = ? ORDER BY ordinal, id
                """, termMapper, schoolId);
    }

    public Optional<PeriodRow> findPeriod(String id) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, term_id, label, start_time, end_time, ordinal
                        FROM period WHERE id = ?
                        """, periodMapper, id)
                .stream().findFirst();
    }

    public List<PeriodRow> listPeriods(String schoolId, String termId) {
        return jdbcTemplate.query("""
                SELECT id, school_id, term_id, label, start_time, end_time, ordinal
                FROM period WHERE school_id = ? AND term_id = ? ORDER BY ordinal, id
                """, periodMapper, schoolId, termId);
    }

    public Optional<CourseNameRow> findCourseName(String id) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, full_name, normalized_name, aliases_json::text AS aliases_json
                        FROM course_name WHERE id = ?
                        """, courseNameMapper, id)
                .stream().findFirst();
    }

    public Optional<CourseNameRow> findCourseNameByNormalized(String schoolId, String normalizedName) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, full_name, normalized_name, aliases_json::text AS aliases_json
                        FROM course_name WHERE school_id = ? AND normalized_name = ?
                        """, courseNameMapper, schoolId, normalizedName)
                .stream().findFirst();
    }

    public List<CourseNameRow> listCourseNames(String schoolId, String q) {
        if (q == null || q.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, school_id, full_name, normalized_name, aliases_json::text AS aliases_json
                    FROM course_name WHERE school_id = ? ORDER BY normalized_name, id
                    """, courseNameMapper, schoolId);
        }
        String pattern = "%" + escapeLike(q) + "%";
        return jdbcTemplate.query("""
                SELECT id, school_id, full_name, normalized_name, aliases_json::text AS aliases_json
                FROM course_name
                WHERE school_id = ?
                  AND (full_name ILIKE ? ESCAPE '\\' OR aliases_json::text ILIKE ? ESCAPE '\\')
                ORDER BY normalized_name, id
                """, courseNameMapper, schoolId, pattern, pattern);
    }

    private List<String> readAliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("course_name.aliases_json 不是字符串数组", ex);
        }
    }

    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
