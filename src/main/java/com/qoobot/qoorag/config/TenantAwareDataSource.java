package com.qoobot.qoorag.config;

import com.qoobot.qoorag.common.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 租户感知数据源（4.9 RLS 兜底）：每次借出连接时，将当前请求租户写入会话级
 * GUC `app.current_tenant`，供数据库 RLS 策略过滤。请求线程未设置租户时写入
 * 空值（视为旁路，供种子/调度等后台任务全量访问）。
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = super.getConnection();
        applyTenant(conn);
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = super.getConnection(username, password);
        applyTenant(conn);
        return conn;
    }

    private void applyTenant(Connection conn) throws SQLException {
        Long tenant = TenantContext.get();
        try (Statement st = conn.createStatement()) {
            if (tenant != null) {
                st.execute("SELECT set_config('app.current_tenant', '" + tenant + "', false)");
            } else {
                // 未设置租户（后台任务）：置空，RLS 策略视为旁路
                st.execute("SELECT set_config('app.current_tenant', '', false)");
            }
        }
    }
}
