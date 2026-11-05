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
        List<String> capabilities = new java.util.ArrayList<>();
        if (hasGlobal(userId, "HELP_CONTENT_WRITE")) {
            capabilities.add("HELP_CONTENT_WRITE");
        }
        Integer support = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant
                WHERE user_id=? AND active=true AND capability='SUPPORT_HANDLE'
                """, Integer.class, userId);
        if (support != null && support > 0) {
            capabilities.add("SUPPORT_HANDLE");
        }
        return List.copyOf(capabilities);
    }

    private boolean hasGlobal(String userId, String capability) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM operator_grant
                WHERE user_id=? AND active=true AND scope_type='GLOBAL' AND capability=?
                """, Integer.class, userId, capability);
        return count != null && count > 0;
    }
}
