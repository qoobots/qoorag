package com.qoobot.qoorag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 将底层 DataSource 包装为租户感知数据源（RLS 兜底）。
 *
 * 直接用 {@code @Value} 注入 {@code spring.datasource.*} 并由 {@link DataSourceBuilder}
 * 构建底层数据源：{@code DataSourceBuilder} 会正确把 {@code url} 映射到 Hikari 的
 * {@code jdbcUrl}（直接依赖自动配置的 {@code DataSourceProperties} 绑定在本项目未生效，
 * 导致 jdbcUrl 为空、Hibernate 无法探测 Dialect）。
 *
 * 注意：{@code @Primary} 的 DataSource 会触发 {@code DataSourceAutoConfiguration} 的
 * {@code @ConditionalOnMissingBean(DataSource.class)} 跳过自动配置的 {@code dataSource} bean，
 * 因此底层数据源必须自行构建（不能依赖自动配置的 {@code dataSource}）。
 */
@Configuration
public class RlsDataSourceConfig {

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName) {
        DataSource delegate = DataSourceBuilder.create()
                .driverClassName(driverClassName)
                .url(url)
                .username(username)
                .password(password)
                .build();
        return new TenantAwareDataSource(delegate);
    }
}
