package com.github.lechandonga.deploymentpipelineorchestratorjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 流水线配置：统一时间源（测试可替换为固定时钟）并启用超时扫描。
 */
@Configuration
@EnableScheduling
public class PipelineConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
