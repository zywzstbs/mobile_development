package cn.gulupai.server.common.clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 统一 UTC 时钟。测试通过 {@code @TestConfiguration} 覆盖为 MutableClock；
 * 演示/验收可通过 {@code app.clock.fixed-instant} 冻结起始时刻。
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(@Value("${app.clock.fixed-instant:}") String fixedInstant,
                       @Value("${app.environment:local}") String environment) {
        rejectProductionFixedClock(environment, fixedInstant);
        if (fixedInstant != null && !fixedInstant.isBlank()) {
            return new MutableClock(Instant.parse(fixedInstant), ZoneOffset.UTC);
        }
        return Clock.systemUTC();
    }

    static void rejectProductionFixedClock(String environment, String fixedInstant) {
        if ("prod".equals(environment) && fixedInstant != null && !fixedInstant.isBlank()) {
            throw new IllegalStateException("生产环境禁止固定时钟");
        }
    }
}
