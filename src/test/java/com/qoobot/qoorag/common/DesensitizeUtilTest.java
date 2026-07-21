package com.qoobot.qoorag.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** DesensitizeUtil 脱敏工具单测（#18） */
class DesensitizeUtilTest {

    @Test
    void maskName_keeps_first_char() {
        assertEquals("张*", DesensitizeUtil.maskName("张三"));
        assertEquals("欧***", DesensitizeUtil.maskName("欧阳娜娜"));
        assertEquals("J***", DesensitizeUtil.maskName("John"));
    }

    @Test
    void maskName_short_or_null() {
        assertNull(DesensitizeUtil.maskName(null));
        assertEquals("", DesensitizeUtil.maskName(""));
        assertEquals("王", DesensitizeUtil.maskName("王"));
    }

    @Test
    void maskPhone_keeps_head_tail() {
        assertEquals("138****5678", DesensitizeUtil.maskPhone("13812345678"));
        assertEquals("138****5678", DesensitizeUtil.maskPhone("13812345678"));
    }

    @Test
    void maskIdCard_18_and_15() {
        assertEquals("110101********1234", DesensitizeUtil.maskIdCard("110101199003071234"));
        assertEquals("110101******123", DesensitizeUtil.maskIdCard("110101900307123"));
    }

    @Test
    void maskText_masks_phone_and_id_in_text() {
        String text = "用户手机号13812345678，身份证110101199003071234";
        String masked = DesensitizeUtil.maskText(text);
        assertTrue(masked.contains("138****5678"));
        assertTrue(masked.contains("110101********1234"));
        assertFalse(masked.contains("13812345678"));
    }
}
