package cn.gulupai.server.common.clock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MutableClockTest {

    @Test
    void advancesDeterministically() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-10-31T00:00:00Z"));
        clock.advance(Duration.ofMinutes(29));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-31T00:29:00Z"));
        clock.advance(Duration.ofMinutes(2));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-31T00:31:00Z"));
    }

    @Test
    void keepsZoneWhenDerived() {
        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
        Clock inShanghai = clock.withZone(java.time.ZoneId.of("Asia/Shanghai"));
        assertThat(inShanghai.instant()).isEqualTo(Instant.EPOCH);
        assertThat(inShanghai.getZone()).isEqualTo(java.time.ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void productionRejectsFixedClock() {
        assertThatThrownBy(() -> ClockConfig.rejectProductionFixedClock("prod", "2026-01-01T00:00:00Z"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境禁止固定时钟");
        ClockConfig.rejectProductionFixedClock("demo", "2026-01-01T00:00:00Z");
        ClockConfig.rejectProductionFixedClock("prod", "");
    }
}
