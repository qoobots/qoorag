package com.qoobot.qoorag.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/** 统一响应包装 Result 的构造与取值校验 */
public class ResultTest {

    @Test
    void ok_with_data() {
        Result ok = Result.ok("data");
        assertEquals(0, ok.getCode());
        assertEquals("ok", ok.getMessage());
        assertEquals("data", ok.getData());
    }

    @Test
    void ok_without_data() {
        Result ok = Result.ok();
        assertEquals(0, ok.getCode());
        assertNull(ok.getData());
    }

    @Test
    void fail_with_default_code() {
        Result fail = Result.fail("boom");
        assertEquals(1, fail.getCode());
        assertEquals("boom", fail.getMessage());
    }

    @Test
    void fail_with_explicit_code() {
        Result fail = Result.fail(42901, "limited");
        assertEquals(42901, fail.getCode());
        assertEquals("limited", fail.getMessage());
    }
}
