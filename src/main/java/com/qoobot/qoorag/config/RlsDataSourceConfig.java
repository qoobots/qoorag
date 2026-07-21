package com.qoobot.qoorag.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/** 将自动装配的 DataSource 包装为租户感知数据源（RLS 兜底） */
@Configuration
public class RlsDataSourceConfig {

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(@Qualifier("dataSource") DataSource delegate) {
        return new TenantAwareDataSource(delegate);
    }
}
