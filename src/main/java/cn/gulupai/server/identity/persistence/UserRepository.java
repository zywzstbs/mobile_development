package cn.gulupai.server.identity.persistence;

import cn.gulupai.server.identity.domain.IdentityStatus;
import cn.gulupai.server.identity.domain.Role;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<UserRow> userMapper = (rs, rowNum) -> new UserRow(
            rs.getString("id"),
            rs.getString("school_id"),
            rs.getString("login_id"),
            rs.getString("student_no"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            IdentityStatus.valueOf(rs.getString("identity_status")),
            rs.getBoolean("disabled"),
            rs.getString("account_status"),
            readSkills(rs.getString("skills_json")),
            rs.getInt("revision"));

    public UserRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<UserRow> findByLoginId(String loginId) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, login_id, student_no, display_name, password_hash,
                               identity_status, disabled, account_status, skills_json, revision
                        FROM app_user
                        WHERE login_id = ?
                        """, userMapper, loginId)
                .stream().findFirst();
    }

    public Map<String, String> displayNames(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, display_name FROM app_user WHERE id IN (" + marks + ")",
                rs -> {
                    names.put(rs.getString("id"), rs.getString("display_name"));
                }, ids.toArray());
        return names;
    }

    public Optional<UserRow> findById(String userId) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, login_id, student_no, display_name, password_hash,
                               identity_status, disabled, account_status, skills_json, revision
                        FROM app_user
                        WHERE id = ?
                        """, userMapper, userId)
                .stream().findFirst();
    }

    /** 用户行是身份更新事务的第一把锁，后续才锁 user_profile。 */
    public Optional<UserRow> findByIdForUpdate(String userId) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, login_id, student_no, display_name, password_hash,
                               identity_status, disabled, account_status, skills_json, revision
                        FROM app_user
                        WHERE id = ?
                        FOR UPDATE
                        """, userMapper, userId)
                .stream().findFirst();
    }

    public Optional<UserRow> findBySchoolAndStudentNo(String schoolId, String studentNo) {
        return jdbcTemplate.query("""
                        SELECT id, school_id, login_id, student_no, display_name, password_hash,
                               identity_status, disabled, account_status, skills_json, revision
                        FROM app_user
                        WHERE school_id = ? AND student_no = ?
                        """, userMapper, schoolId, studentNo)
                .stream().findFirst();
    }

    public List<Role> findRoles(String userId) {
        List<Role> roles = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT role FROM user_role WHERE user_id = ?", String.class, userId)
                .stream().map(Role::valueOf).toList());
        roles.sort(Comparator.comparingInt(Enum::ordinal));
        return roles;
    }

    public int updateBasics(String userId, String displayName, String skillsJson, int expectedRevision) {
        return jdbcTemplate.update("""
                UPDATE app_user
                SET display_name = ?, skills_json = ?::jsonb, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND revision = ?
                """, displayName, skillsJson, userId, expectedRevision);
    }

    public int updatePasswordHash(String userId, String passwordHash) {
        return jdbcTemplate.update("""
                UPDATE app_user
                SET password_hash = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, passwordHash, userId);
    }

    public Optional<ProfileRow> findProfile(String userId) {
        return jdbcTemplate.query("""
                        SELECT user_id, intro, profile_json::text AS profile_json,
                               visibility_json::text AS visibility_json, revision
                        FROM user_profile
                        WHERE user_id = ?
                        """, profileMapper, userId)
                .stream().findFirst();
    }

    public Optional<ProfileRow> findProfileForUpdate(String userId) {
        return jdbcTemplate.query("""
                        SELECT user_id, intro, profile_json::text AS profile_json,
                               visibility_json::text AS visibility_json, revision
                        FROM user_profile
                        WHERE user_id = ?
                        FOR UPDATE
                        """, profileMapper, userId)
                .stream().findFirst();
    }

    public void insertProfile(String userId, String intro, String profileJson, String visibilityJson) {
        jdbcTemplate.update("""
                INSERT INTO user_profile (user_id, intro, profile_json, visibility_json, revision)
                VALUES (?, ?, ?::jsonb, ?::jsonb, 1)
                """, userId, intro, profileJson, visibilityJson);
    }

    public int updateProfileFields(String userId, String intro, String profileJson) {
        return jdbcTemplate.update("""
                UPDATE user_profile
                SET intro = ?, profile_json = ?::jsonb, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """, intro, profileJson, userId);
    }

    public int updateVisibilityFields(String userId, String visibilityJson) {
        return jdbcTemplate.update("""
                UPDATE user_profile
                SET visibility_json = ?::jsonb, revision = revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """, visibilityJson, userId);
    }

    private List<String> readSkills(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("app_user.skills_json 不是字符串数组", ex);
        }
    }

    private final RowMapper<ProfileRow> profileMapper = (rs, rowNum) -> new ProfileRow(
            rs.getString("user_id"),
            rs.getString("intro"),
            rs.getString("profile_json"),
            rs.getString("visibility_json"),
            rs.getInt("revision"));
}
