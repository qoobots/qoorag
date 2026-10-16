package com.qoobot.qoorag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RAG-022：父子块分组逻辑单测（纯函数，规避 JDK25/Mockito 预存问题） */
public class IngestParentGroupingTest {

    private IngestService.ChunkDraft d(String content) {
        return new IngestService.ChunkDraft(content, null, 0, 1, "paragraph");
    }

    @Test
    void groupsContiguousDrafts() {
        List<IngestService.ChunkDraft> drafts = List.of(
                d("a"), d("b"), d("c"), d("d"), d("e"));
        List<IngestService.ParentGroup> groups = IngestService.groupIntoParents(drafts, 2);

        assertEquals(3, groups.size());
        assertEquals(List.of(0, 1), groups.get(0).childIndices());
        assertEquals("a\n\nb", groups.get(0).parentContent());
        assertEquals(List.of(2, 3), groups.get(1).childIndices());
        assertEquals("c\n\nd", groups.get(1).parentContent());
        assertEquals("e", groups.get(2).parentContent());
    }

    @Test
    void groupSizeOneKeepsEachAlone() {
        List<IngestService.ChunkDraft> drafts = List.of(d("x"), d("y"));
        List<IngestService.ParentGroup> groups = IngestService.groupIntoParents(drafts, 1);
        assertEquals(2, groups.size());
        assertEquals("x", groups.get(0).parentContent());
        assertEquals("y", groups.get(1).parentContent());
    }

    @Test
    void groupSizeLargerThanCountProducesSingleParent() {
        List<IngestService.ChunkDraft> drafts = List.of(d("p"), d("q"), d("r"));
        List<IngestService.ParentGroup> groups = IngestService.groupIntoParents(drafts, 10);
        assertEquals(1, groups.size());
        assertEquals(List.of(0, 1, 2), groups.get(0).childIndices());
        assertEquals("p\n\nq\n\nr", groups.get(0).parentContent());
    }
}
