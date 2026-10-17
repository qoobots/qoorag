package com.qoobot.qoorag.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MilvusVectorStore 纯逻辑单测（不依赖 Milvus 服务 / 不使用 Mockito，规避 JDK25 限制） */
public class MilvusVectorStoreTest {

    @Test
    void buildFilter_formats_scalar_expr() {
        assertEquals("kb_id == 7 && tenant_id == 3", MilvusVectorStore.buildFilter(7L, 3L));
        assertEquals("kb_id == 0 && tenant_id == -1", MilvusVectorStore.buildFilter(0L, -1L));
    }

    @Test
    void toFloatList_converts_array() {
        List<Float> list = MilvusVectorStore.toFloatList(new float[]{0.1f, 0.2f, 0.3f});
        assertEquals(3, list.size());
        assertEquals(0.2f, list.get(1), 1e-6);
    }

    @Test
    void toFloatList_nullSafe() {
        assertTrue(MilvusVectorStore.toFloatList(null).isEmpty());
    }
}
