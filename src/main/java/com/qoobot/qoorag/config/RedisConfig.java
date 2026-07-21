package com.qoobot.qoorag.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qoobot.qoorag.common.SessionInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Redis 配置：会话存储（JSON 序列化 SessionInfo）+ 限流计数 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, SessionInfo> sessionRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, SessionInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());

        // 明确指定目标类型为 SessionInfo，避免 GenericJackson2JsonRedisSerializer 反序列化为 LinkedHashMap
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // 兼容旧 GenericJackson 序列化数据中的多余字段（如 isApiKey 同时序列化为 apiKey）
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Jackson2JsonRedisSerializer<SessionInfo> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, SessionInfo.class);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
