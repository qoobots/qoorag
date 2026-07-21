package com.qoobot.qoorag.common;

/** 统一错误码（对齐 05.4 接口设计错误码规范） */
public final class ErrorCode {
    public static final int PARAM_INVALID   = 40001; // 参数校验失败
    public static final int UNAUTHENTICATED = 40101; // 未认证 / 令牌缺失或无效
    public static final int APIKEY_INVALID  = 40102; // API Key 无效或已吊销
    public static final int FORBIDDEN       = 40301; // 越权 / 租户隔离拦截
    public static final int RATE_LIMITED    = 42901; // 触发速率限制
    public static final int CONTENT_BLOCKED = 40040; // 内容安全拦截（#18）
    public static final int INTERNAL        = 50001; // 内部错误

    private ErrorCode() {}
}
