package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.service.RetrievalEvalService;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RetrievalEvalController Web 测试：data-classifications 与 eval 接口及参数校验 */
public class RetrievalEvalControllerTest {

    RetrievalEvalService evalService = mock(RetrievalEvalService.class);
    RetrievalEvalController controller = new RetrievalEvalController(evalService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        SessionInfo info = new SessionInfo();
        info.tenantId = 7L;
        SecurityContext.set(info);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void data_classifications_returns_five_levels() throws Exception {
        mockMvc.perform(get("/api/admin/data-classifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].code").value("PUBLIC"))
                .andExpect(jsonPath("$.data[4].code").value("SECRET"))
                .andExpect(jsonPath("$.data[4].rank").value(5));
    }

    @Test
    void eval_returns_aggregated_metrics() throws Exception {
        when(evalService.evaluateDataset(any(), anyInt(), eq(2L), eq(7L)))
                .thenReturn(new RetrievalEvalService.EvalMetrics(0.5, 0.2, 0.5, 0.5, 2, List.of()));

        String body = "{"
                + "\"kbId\":2,\"topK\":5,"
                + "\"queries\":[{\"query\":\"q1\",\"relevantChunkIds\":[10,11]}]"
                + "}";
        mockMvc.perform(post("/api/admin/eval").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recallAtK").value(0.5))
                .andExpect(jsonPath("$.data.precisionAtK").value(0.2))
                .andExpect(jsonPath("$.data.hitRate").value(0.5))
                .andExpect(jsonPath("$.data.mrr").value(0.5))
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    void eval_missing_queries_throws_40001() throws Exception {
        String body = "{\"kbId\":2,\"queries\":[]}";
        mockMvc.perform(post("/api/admin/eval").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
