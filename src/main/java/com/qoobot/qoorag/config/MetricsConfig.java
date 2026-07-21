package com.qoobot.qoorag.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** RAG 链路监控埋点（QPS / 延迟 / 命中率），通过 Micrometer 暴露给 Actuator */
@Configuration
public class MetricsConfig {

    @Bean
    public RagMetrics ragMetrics(MeterRegistry registry) {
        return new RagMetrics(registry);
    }

    /** 检索 / 问答指标收集器 */
    public static class RagMetrics {
        private final Timer retrieveTimer;
        private final Timer chatTimer;
        private final Counter retrieveHit;
        private final Counter retrieveMiss;
        private final Counter chatCalls;

        public RagMetrics(MeterRegistry registry) {
            this.retrieveTimer = Timer.builder("qoorag.retrieve.latency")
                    .description("检索接口延迟").register(registry);
            this.chatTimer = Timer.builder("qoorag.chat.latency")
                    .description("问答接口延迟").register(registry);
            this.retrieveHit = Counter.builder("qoorag.retrieve.hits")
                    .description("检索有命中块的次数").register(registry);
            this.retrieveMiss = Counter.builder("qoorag.retrieve.misses")
                    .description("检索无命中块的次数").register(registry);
            this.chatCalls = Counter.builder("qoorag.chat.calls")
                    .description("问答调用次数").register(registry);
        }

        public void recordRetrieve(long millis, boolean hasHit) {
            retrieveTimer.record(millis, TimeUnit.MILLISECONDS);
            if (hasHit) {
                retrieveHit.increment();
            } else {
                retrieveMiss.increment();
            }
        }

        public void recordChat(long millis) {
            chatTimer.record(millis, TimeUnit.MILLISECONDS);
            chatCalls.increment();
        }
    }
}
