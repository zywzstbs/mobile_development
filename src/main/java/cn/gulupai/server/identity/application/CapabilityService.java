package cn.gulupai.server.identity.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录和 /me 的能力提示。只投影有效的全局 HELP_CONTENT_WRITE，班级范围授权不升成全局能力。
 */
@Service
public class CapabilityService {

    private final JdbcTemplate jdbc;

    public CapabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> forUser(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant
                WHERE user_id=? AND active=true AND scope_type='GLOBAL'
                  AND capability='HELP_CONTENT_WRITE'
                """, Integer.class, userId);
        if (count != null && count > 0) {
            return List.of("HELP_CONTENT_WRITE");
        }
        return List.of();
    }
}
