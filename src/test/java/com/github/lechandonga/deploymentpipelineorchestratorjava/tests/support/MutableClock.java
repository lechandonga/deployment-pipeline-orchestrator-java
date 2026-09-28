package com.github.lechandonga.deploymentpipelineorchestratorjava.tests.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可变时钟：初始固定时间，可手动前进，用于确定性地验证超时。
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone = ZoneId.of("UTC");

    public MutableClock(Instant start) {
        this.now = start;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }
}
