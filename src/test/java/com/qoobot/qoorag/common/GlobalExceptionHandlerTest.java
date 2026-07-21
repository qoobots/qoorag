package com.qoobot.qoorag.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GlobalExceptionHandler 单元测试：通过 standalone MockMvc + 抛出异常的测试控制器验证错误码映射 */
public class GlobalExceptionHandlerTest {

    static class Req {
        @NotBlank
        public String name;
    }

    @RestController
    static class TestController {
        @PostMapping("/biz")
        public Result biz() {
            throw new BizException(ErrorCode.FORBIDDEN, "forbidden");
        }

        @PostMapping("/valid")
        public Result valid(@RequestBody @Valid Req req) {
            return Result.ok(req.name);
        }

        @PostMapping("/header")
        public Result header(@RequestHeader("X-Required") String h) {
            return Result.ok(h);
        }

        @PostMapping("/badjson")
        public Result badjson(@RequestBody Req req) {
            return Result.ok("x");
        }

        @PostMapping("/generic")
        public Result generic() {
            throw new RuntimeException("boom");
        }
    }

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bizException_maps_to_code() throws Exception {
        mockMvc.perform(post("/biz").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    void validation_maps_to_40001() throws Exception {
        mockMvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void missingHeader_maps_to_40101() throws Exception {
        mockMvc.perform(post("/header").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void badJson_maps_to_40001() throws Exception {
        mockMvc.perform(post("/badjson").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void generic_maps_to_50001() throws Exception {
        mockMvc.perform(post("/generic").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(50001));
    }
}
