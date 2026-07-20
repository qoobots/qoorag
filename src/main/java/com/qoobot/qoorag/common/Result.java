package com.qoobot.qoorag.common;

import java.util.Map;

/** 统一 API 响应包装 */
public class Result {
    private int code;
    private String message;
    private Object data;

    public Result(int code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static Result ok(Object data) {
        return new Result(0, "ok", data);
    }

    public static Result ok() {
        return new Result(0, "ok", null);
    }

    public static Result fail(String message) {
        return new Result(1, message, null);
    }

    public static Result fail(int code, String message) {
        return new Result(code, message, null);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public Object getData() { return data; }
}
