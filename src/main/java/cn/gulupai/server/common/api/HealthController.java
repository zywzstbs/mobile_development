package cn.gulupai.server.common.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * 最小健康检查，不属于 281 个业务接口，不进入接口覆盖清单。
 * 数据库不可用时返回 503 统一信封，而不是伪 UP。
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final String environment;
    private final String version;

    public HealthController(JdbcTemplate jdbcTemplate,
                            Clock clock,
                            @Value("${app.environment:local}") String environment,
                            @Value("${app.version:0.1.0-P00}") String version) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.environment = environment;
        this.version = version;
    }

    @GetMapping("/health")
    public ApiResponse<HealthData> health() {
        try {
            Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
            if (one == null || one != 1) {
                throw new IllegalStateException("数据库健康检查结果异常");
            }
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE);
        }
        return ApiResponse.ok(new HealthData("UP", "UP", environment, version, clock.instant()));
    }

    public record HealthData(String status, String database, String environment, String version, Instant time) {
    }
}
