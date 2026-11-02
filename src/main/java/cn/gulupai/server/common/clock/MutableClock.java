package cn.gulupai.server.common.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 可推进的测试/演示时钟。业务代码只依赖 {@link Clock}，测试可精确推进到期边界，
 * 不用 sleep 假定时间先后。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneOffset.UTC);
    }

    public synchronized void setInstant(Instant instant) {
        this.instant = Objects.requireNonNull(instant, "instant");
    }

    public synchronized void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public synchronized Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant(), zone);
    }
}
