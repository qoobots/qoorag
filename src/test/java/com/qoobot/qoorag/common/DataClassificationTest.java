package com.qoobot.qoorag.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DataClassification 枚举：fromCode 校验、listAll、rank 排序 */
public class DataClassificationTest {

    @Test
    void fromCode_matches_case_insensitively() {
        assertEquals(DataClassification.INTERNAL, DataClassification.fromCode("internal"));
        assertEquals(DataClassification.SECRET, DataClassification.fromCode(" SECRET "));
        assertEquals(DataClassification.PUBLIC, DataClassification.fromCode("PUBLIC"));
    }

    @Test
    void fromCode_invalid_throws_40001() {
        BizException ex = assertThrows(BizException.class, () -> DataClassification.fromCode("TOPSECRET"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }

    @Test
    void fromCode_null_or_blank_throws() {
        assertThrows(BizException.class, () -> DataClassification.fromCode(null));
        assertThrows(BizException.class, () -> DataClassification.fromCode("  "));
    }

    @Test
    void listAll_returns_five_levels() {
        List<DataClassification> all = DataClassification.listAll();
        assertEquals(5, all.size());
        assertEquals(DataClassification.PUBLIC, all.get(0));
        assertEquals(DataClassification.SECRET, all.get(4));
    }

    @Test
    void rank_increases_with_sensitivity() {
        assertTrue(DataClassification.INTERNAL.atLeast(DataClassification.PUBLIC));
        assertTrue(DataClassification.SECRET.atLeast(DataClassification.CONFIDENTIAL));
        assertTrue(DataClassification.CONFIDENTIAL.rank > DataClassification.RESTRICTED.rank);
    }
}
