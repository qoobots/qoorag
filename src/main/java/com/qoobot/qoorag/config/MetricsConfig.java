package com.qoobot.qoorag.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * RAG 链路监控埋点（QPS / 延迟 / 失败率），通过 Micrometer 暴露给 Actuator。
 * <p>
 * RAG-054：在原有「检索/问答」延迟与命中计数基础上，扩展为检索 / 问答 / 入库 / LLM / Embedding
 * 五类 Timer（均 {@code publishPercentiles(0.5/0.95/0.99)} 暴露 P50/P95/P99），并为每类增加
 * 失败 Counter，使「P95 延迟」与「失败率」可直接入 Prometheus 并对接告警规则。
 */
@Configuration
public class MetricsConfig {

    @Bean
    public RagMetrics ragMetrics(MeterRegistry registry) {
        return new RagMetrics(registry);
    }

    /** 检索 / 问答 / 入库 / LLM / Embedding 指标收集器 */
    public static class RagMetrics {
        private final Timer retrieveTimer;
        private final Timer chatTimer;
        private final Timer ingestTimer;
        private final Timer llmTimer;
        private final Timer embeddingTimer;

        private final Counter retrieveHit;
        private final Counter retrieveMiss;
        private final Counter chatCalls;
        private final Counter retrieveFailures;
        private final Counter chatFailures;
        private final Counter ingestSuccess;
        private final Counter ingestFailures;
        private final Counter llmSuccess;
        private final Counter llmFailures;
        private final Counter embeddingSuccess;
        private final Counter embeddingFailures;

        public RagMetrics(MeterRegistry registry) {
            this.retrieveTimer = Timer.builder("qoorag.retrieve.latency")
                    .description("检索接口延迟").publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram(true).register(registry);
            this.chatTimer = Timer.builder("qoorag.chat.latency")
                    .description("问答接口延迟").publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram(true).register(registry);
            this.ingestTimer = Timer.builder("qoorag.ingest.latency")
                    .description("文档入库延迟").publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram(true).register(registry);
            this.llmTimer = Timer.builder("qoorag.llm.latency")
                    .description("LLM 生成调用延迟").publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram(true).register(registry);
            this.embeddingTimer = Timer.builder("qoorag.embedding.latency")
                    .description("Embedding 调用延迟").publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram(true).register(registry);

            this.retrieveHit = Counter.builder("qoorag.retrieve.hits")
                    .description("检索有命中块的次数").register(registry);
            this.retrieveMiss = Counter.builder("qoorag.retrieve.misses")
                    .description("检索无命中块的次数").register(registry);
            this.chatCalls = Counter.builder("qoorag.chat.calls")
                    .description("问答调用次数").register(registry);
            this.retrieveFailures = Counter.builder("qoorag.retrieve.failures")
                    .description("检索失败次数").register(registry);
            this.chatFailures = Counter.builder("qoorag.chat.failures")
                    .description("问答失败次数").register(registry);
            this.ingestSuccess = Counter.builder("qoorag.ingest.success")
                    .description("入库成功次数").register(registry);
            this.ingestFailures = Counter.builder("qoorag.ingest.failures")
                    .description("入库失败次数").register(registry);
            this.llmSuccess = Counter.builder("qoorag.llm.success")
                    .description("LLM 调用成功次数").register(registry);
            this.llmFailures = Counter.builder("qoorag.llm.failures")
                    .description("LLM 调用失败次数").register(registry);
            this.embeddingSuccess = Counter.builder("qoorag.embedding.success")
                    .description("Embedding 调用成功次数").register(registry);
            this.embeddingFailures = Counter.builder("qoorag.embedding.failures")
                    .description("Embedding 调用失败次数").register(registry);
        }

        public void recordRetrieve(long millis, boolean hasHit) {
            retrieveTimer.record(millis, TimeUnit.MILLISECONDS);
            if (hasHit) {
                retrieveHit.increment();
            } else {
                retrieveMiss.increment();
            }
        }

        public void recordRetrieveFailure() {
            retrieveFailures.increment();
        }

        public void recordChat(long millis) {
            chatTimer.record(millis, TimeUnit.MILLISECONDS);
            chatCalls.increment();
        }

        public void recordChatFailure() {
            chatFailures.increment();
        }

        public void recordIngest(long millis, boolean success) {
            ingestTimer.record(millis, TimeUnit.MILLISECONDS);
            if (success) {
                ingestSuccess.increment();
            } else {
                ingestFailures.increment();
            }
        }

        public void recordLlm(long millis, boolean success) {
            llmTimer.record(millis, TimeUnit.MILLISECONDS);
            if (success) {
                llmSuccess.increment();
            } else {
                llmFailures.increment();
            }
        }

        public void recordEmbedding(long millis, boolean success) {
            embeddingTimer.record(millis, TimeUnit.MILLISECONDS);
            if (success) {
                embeddingSuccess.increment();
            } else {
                embeddingFailures.increment();
            }
        }
    }
}
