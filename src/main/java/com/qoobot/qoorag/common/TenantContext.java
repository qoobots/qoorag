package com.qoobot.qoorag.common;

/** 请求级租户上下文（ThreadLocal），供 RLS 注入 app.current_tenant 使用 */
public class TenantContext {
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long tenantId) {
        HOLDER.set(tenantId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
