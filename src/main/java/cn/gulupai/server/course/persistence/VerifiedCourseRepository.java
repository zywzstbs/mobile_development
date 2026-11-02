package cn.gulupai.server.course.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class VerifiedCourseRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<VerifiedCourseRow> mapper = (rs, rowNum) -> new VerifiedCourseRow(
            rs.getString("id"),
            rs.getString("school_id"),
            rs.getString("term_id"),
            rs.getString("course_name_id"),
            rs.getString("full_name"),
            rs.getString("teacher_user_id"),
            rs.getString("canonical_key"));

    public VerifiedCourseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VerifiedCourseRow> findByCanonicalKey(String canonicalKey) {
        return jdbcTemplate.query("""
                        SELECT vc.id, vc.school_id, vc.term_id, vc.course_name_id,
                               cn.full_name, vc.teacher_user_id, vc.canonical_key
                        FROM verified_course vc
                        JOIN course_name cn ON cn.id = vc.course_name_id
                        WHERE vc.canonical_key = ?
                        """, mapper, canonicalKey)
                .stream().findFirst();
    }

    public Optional<VerifiedCourseRow> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT vc.id, vc.school_id, vc.term_id, vc.course_name_id,
                               cn.full_name, vc.teacher_user_id, vc.canonical_key
                        FROM verified_course vc
                        JOIN course_name cn ON cn.id = vc.course_name_id
                        WHERE vc.id = ?
                        """, mapper, id)
                .stream().findFirst();
    }

    public Optional<VerifiedCourseRow> findByExternalKey(String schoolId, String termId, String externalKey) {
        return jdbcTemplate.query("""
                        SELECT vc.id, vc.school_id, vc.term_id, vc.course_name_id,
                               cn.full_name, vc.teacher_user_id, vc.canonical_key
                        FROM verified_course vc
                        JOIN course_name cn ON cn.id = vc.course_name_id
                        WHERE vc.school_id = ? AND vc.term_id = ? AND vc.external_key = ?
                        """, mapper, schoolId, termId, externalKey)
                .stream().findFirst();
    }

    public boolean isValidEnrollment(String verifiedCourseId, String userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM verified_enrollment
                    WHERE verified_course_id = ? AND user_id = ? AND valid = true
                )
                """, Boolean.class, verifiedCourseId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<Integer> enrollmentRevision(String verifiedCourseId, String userId) {
        return jdbcTemplate.query("""
                        SELECT revision FROM verified_enrollment
                        WHERE verified_course_id = ? AND user_id = ? AND valid = true
                        """, (rs, rowNum) -> rs.getInt("revision"), verifiedCourseId, userId)
                .stream().findFirst();
    }

    public boolean isCurrentTeacher(String verifiedCourseId, String userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM verified_course
                    WHERE id = ? AND teacher_user_id = ?
                )
                """, Boolean.class, verifiedCourseId, userId);
        return Boolean.TRUE.equals(exists);
    }
}
