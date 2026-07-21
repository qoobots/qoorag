package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.dto.ResourcePoolItem;
import com.qoobot.qoorag.service.ResourcePoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class ResourcePoolControllerTest {

    @Mock ResourcePoolService service;
    @InjectMocks ResourcePoolController controller;
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void list_returnsGrouped() throws Exception {
        Map<String, List<ResourcePoolItem>> data = new LinkedHashMap<>();
        data.put("LLM", List.of(ResourcePoolItem.of("LLM", "chat_model", "qwen-plus", false, "model")));
        when(service.listGrouped()).thenReturn(data);

        mockMvc.perform(get("/api/admin/resource-pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.LLM[0].configKey").value("chat_model"));
    }

    @Test
    void save_returnsItem() throws Exception {
        ResourcePoolItem item = ResourcePoolItem.of("LLM", "chat_model", "qwen-max", false, "model");
        when(service.save(any(), any(), any(), any())).thenReturn(item);

        mockMvc.perform(put("/api/admin/resource-pool")
                        .contentType("application/json")
                        .content("{\"category\":\"LLM\",\"configKey\":\"chat_model\",\"configValue\":\"qwen-max\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.configValue").value("qwen-max"));
    }

    @Test
    void save_missingCategory_returns40001() throws Exception {
        mockMvc.perform(put("/api/admin/resource-pool")
                        .contentType("application/json")
                        .content("{\"configKey\":\"chat_model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
