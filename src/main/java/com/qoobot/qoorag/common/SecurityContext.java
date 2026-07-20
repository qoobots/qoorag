package com.qoobot.qoorag.common;

/** 基于 ThreadLocal 的请求级安全上下文（由 AuthInterceptor 写入/清除） */
public class SecurityContext {
    private static final ThreadLocal<SessionInfo> HOLDER = new ThreadLocal<>();

    public static void set(SessionInfo info) {
        HOLDER.set(info);
    }

    public static SessionInfo get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
