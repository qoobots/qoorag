package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 阿里云内容安全（绿网）文本检测实现（#18）。
 * <p>默认关闭（{@code qoorag.content-safety.enabled=false}）；关闭或无密钥时 fail-open 返回 SAFE，不阻断业务。
 * 开启且配置密钥后调用阿里云绿网文本检测接口；异常同样 fail-open 放行。
 * <p>注意：正式接入需在具备阿里云凭证的环境按官方文档校验 endpoint / 签名 / 响应字段
 * （当前 {@link #buildHeaders} / {@link #buildBody} 为请求骨架，已在代码中标注 TODO）。
 */
@Service
public class AliyunContentSafetyService implements ContentSafetyService {

    private static final Logger log = LoggerFactory.getLogger(AliyunContentSafetyService.class);

    @Value("${qoorag.content-safety.enabled:false}")
    private boolean enabled;

    @Value("${qoorag.content-safety.aliyun.access-key:}")
    private String accessKey;

    @Value("${qoorag.content-safety.aliyun.secret-key:}")
    private String secretKey;

    @Value("${qoorag.content-safety.aliyun.region:cn-shanghai}")
    private String region;

    @Value("${qoorag.content-safety.aliyun.endpoint:https://green.cn-shanghai.aliyuncs.com}")
    private String endpoint;

    private final RestTemplate restTemplate;

    public AliyunContentSafetyService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Result checkText(String text) {
        if (!enabled) {
            return Result.safe();
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("内容安全已启用但未配置阿里云密钥，fail-open 放行");
            return Result.safe();
        }
        if (text == null || text.isBlank()) {
            return Result.safe();
        }
        try {
            // TODO: 接入阿里云内容安全（绿网）正式签名与报文；以下为请求骨架，
            //       需在具备阿里云凭证的环境按官方文档校验 endpoint/签名/响应字段。
            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(buildBody(text), headers);
            String resp = restTemplate.postForObject(endpoint + "/green/text/scan", entity, String.class);
            return parse(resp);
        } catch (Exception e) {
            log.warn("内容安全检测异常，fail-open 放行: {}", e.getMessage());
            return Result.safe();
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // TODO: 按阿里云绿网要求补全签名头（如 x-acs-signature / Authorization 等）
        h.set("Authorization", "ACS " + accessKey);
        h.set("X-Acs-Region-Id", region);
        return h;
    }

    private String buildBody(String text) {
        return "{\"tasks\":[{\"content\":\"" + text.replace("\"", "\\\"") + "\"}]}";
    }

    /** 解析阿里云绿网响应：suggestion=pass/review 视为安全，block/blocked 视为拦截。包级可见便于单测。 */
    Result parse(String body) {
        if (body == null) {
            return Result.safe();
        }
        if (body.contains("\"block\"") || body.contains("\"blocked\"")) {
            return Result.blocked("命中阿里云内容安全拦截");
        }
        return Result.safe();
    }
}
