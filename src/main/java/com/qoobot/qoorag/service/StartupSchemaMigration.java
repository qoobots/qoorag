package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期轻量 schema 迁移：审计日志 actor_id 解除对 users 的强制外键。
 * <p>审计日志的操作人可能是「用户会话」(users.id) 或「API Key」(api_key.id)，
 * 原外键仅指向 users，导致 API Key 调用写审计日志时外键冲突。
 * 移除外键后 actor_id 可同时承载两种主体（审计日志允许引用已删除/外部主体）。
 */
@Component
@Order(5)
public class StartupSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public StartupSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_actor_id_fkey");
            log.info("审计日志 actor_id 外键已移除（支持 API Key 作为操作人）");
        } catch (Exception e) {
            log.warn("审计日志外键迁移跳过（可忽略，不影响启动）: {}", e.getMessage());
        }
    }
}
