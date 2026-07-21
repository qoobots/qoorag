package com.qoobot.qoorag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AliyunContentSafetyService 单测（#18）：覆盖关闭/无密钥 fail-open、命中拦截、异常 fail-open */
@ExtendWith(MockitoExtension.class)
class AliyunContentSafetyServiceTest {

    @Mock
    RestTemplate restTemplate;

    private AliyunContentSafetyService service() {
        return new AliyunContentSafetyService(restTemplate);
    }

    @Test
    void disabled_returns_safe() {
        AliyunContentSafetyService s = service();
        ReflectionTestUtils.setField(s, "enabled", false);
        assertEquals(ContentSafetyService.Verdict.SAFE, s.checkText("违规内容").verdict);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void enabled_without_key_returns_safe() {
        AliyunContentSafetyService s = service();
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "accessKey", "");
        ReflectionTestUtils.setField(s, "secretKey", "");
        assertEquals(ContentSafetyService.Verdict.SAFE, s.checkText("违规内容").verdict);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void enabled_with_key_blocked_response() {
        AliyunContentSafetyService s = service();
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "accessKey", "ak");
        ReflectionTestUtils.setField(s, "secretKey", "sk");
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{\"suggestion\":\"block\"}");
        ContentSafetyService.Result r = s.checkText("违规内容");
        assertEquals(ContentSafetyService.Verdict.BLOCKED, r.verdict);
    }

    @Test
    void enabled_with_key_pass_response() {
        AliyunContentSafetyService s = service();
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "accessKey", "ak");
        ReflectionTestUtils.setField(s, "secretKey", "sk");
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{\"suggestion\":\"pass\"}");
        assertEquals(ContentSafetyService.Verdict.SAFE, s.checkText("正常内容").verdict);
    }

    @Test
    void enabled_exception_fail_open() {
        AliyunContentSafetyService s = service();
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "accessKey", "ak");
        ReflectionTestUtils.setField(s, "secretKey", "sk");
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("network error"));
        assertEquals(ContentSafetyService.Verdict.SAFE, s.checkText("正常内容").verdict);
    }
}
