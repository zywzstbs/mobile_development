package cn.gulupai.server.support;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 通用帮助离线包的单调版本。可见集合或输出内容变化时加一。
 * 幂等回放不调用这里，因此不会重复推进。
 */
@Component
public class HelpContentPack {

    public static final String GENERAL = "GENERAL";

    private final JdbcTemplate jdbc;

    public HelpContentPack(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void advance(OffsetDateTime at) {
        int updated = jdbc.update("""
                UPDATE help_content_pack
                SET version = version + 1, updated_at = ?
                WHERE id = ? AND version < 999999999
                """, at, GENERAL);
        if (updated != 1) {
            throw new ApiException(ApiCode.SERVER_ERROR, "帮助包版本没有推进");
        }
    }

    public static boolean visible(String audience, boolean published) {
        return "GENERAL".equals(audience) && published;
    }
}
