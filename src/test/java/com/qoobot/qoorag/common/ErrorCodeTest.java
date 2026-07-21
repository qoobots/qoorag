package com.qoobot.qoorag.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** 错误码常量与 05.4 接口设计错误码规范对齐校验 */
public class ErrorCodeTest {

    @Test
    void constants_match_spec() {
        assertEquals(40001, ErrorCode.PARAM_INVALID);
        assertEquals(40101, ErrorCode.UNAUTHENTICATED);
        assertEquals(40102, ErrorCode.APIKEY_INVALID);
        assertEquals(40301, ErrorCode.FORBIDDEN);
        assertEquals(42901, ErrorCode.RATE_LIMITED);
        assertEquals(50001, ErrorCode.INTERNAL);
    }
}
