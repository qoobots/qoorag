package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.EvalDataset;
import com.qoobot.qoorag.entity.EvalDatasetItem;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.EvalDatasetItemRepository;
import com.qoobot.qoorag.repository.EvalDatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测集管理（RAG-001 / RAG-002）。
 * <p>
 * 解决改造前的三个问题：
 * <ol>
 *   <li><b>不可扩展</b>：只有 1 份手写 JSON → 支持按 KB 多版本、程序化生成；</li>
 *   <li><b>标注粒度粗</b>：全部文档级标注 → 支持 chunk 级标注（与文档级可混用，判定逻辑取并集）；</li>
 *   <li><b>无法回溯</b>：不知道标注怎么来的 → 自动生成的条目带 {@code originQuery}。</li>
 * </ol>
 * 导入导出格式与既有 {@code POST /api/admin/eval} 的 {@code queries} 入参完全一致，
 * 因此现有 {@code scripts/eval/*.ps1} 无需改动即可继续使用。
 */
@Service
public class EvalDatasetService {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetService.class);

    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetItemRepository itemRepository;
    private final ChunkRepository chunkRepository;

    public EvalDatasetService(EvalDatasetRepository datasetRepository,
                              EvalDatasetItemRepository itemRepository,
                              ChunkRepository chunkRepository) {
        this.datasetRepository = datasetRepository;
        this.itemRepository = itemRepository;
        this.chunkRepository = chunkRepository;
    }

    /** 一条待写入的条目（chunk 级 + 文档级标注均可） */
    public record EvalItem(String query, Set<Long> relevantChunkIds, Set<Long> relevantDocIds,
                           String originQuery) {
        public EvalItem {
            relevantChunkIds = relevantChunkIds == null ? Set.of() : relevantChunkIds;
            relevantDocIds = relevantDocIds == null ? Set.of() : relevantDocIds;
        }
    }

    /**
     * 创建评测集并写入条目。
     *
     * @param kbId    知识库
     * @param tenantId 租户
     * @param name    名称
     * @param source  MANUAL / GENERATED
     * @param items   条目
     * @param userId  创建人
     */
    @Transactional
    public EvalDataset create(Long kbId, Long tenantId, String name, String source,
                              List<EvalItem> items, Long userId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("评测集条目不能为空");
        }
        int nextVersion = datasetRepository.findFirstByKbIdOrderByVersionDesc(kbId) == null
                ? 1 : datasetRepository.findFirstByKbIdOrderByVersionDesc(kbId).getVersion() + 1;

        EvalDataset ds = new EvalDataset();
        ds.setKbId(kbId);
        ds.setTenantId(tenantId);
        ds.setName(name == null || name.isBlank() ? "评测集 v" + nextVersion : name);
        ds.setSource(source == null ? EvalDataset.SOURCE_MANUAL : source);
        ds.setVersion(nextVersion);
        ds.setItemCount(items.size());
        ds.setCreatedBy(userId);
        ds.setCreatedAt(LocalDateTime.now());
        ds = datasetRepository.save(ds);

        List<EvalDatasetItem> rows = new ArrayList<>(items.size());
        for (EvalItem it : items) {
            if (it.query() == null || it.query().isBlank()) {
                continue; // 跳过空 query
            }
            EvalDatasetItem row = new EvalDatasetItem();
            row.setDatasetId(ds.getId());
            row.setQuery(it.query());
            row.setRelevantChunkIds(join(it.relevantChunkIds()));
            row.setRelevantDocIds(join(it.relevantDocIds()));
            row.setOriginQuery(it.originQuery());
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        itemRepository.saveAll(rows);
        ds.setItemCount(rows.size());
        log.info("评测集已创建：id={} kbId={} source={} 条目={}", ds.getId(), kbId, ds.getSource(), rows.size());
        return datasetRepository.save(ds);
    }

    /** 列出某 KB 的评测集 */
    public List<EvalDataset> list(Long kbId) {
        return datasetRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    /** 取评测集（带 KB 归属校验） */
    public EvalDataset get(Long kbId, Long datasetId) {
        EvalDataset ds = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new RuntimeException("评测集不存在"));
        if (!ds.getKbId().equals(kbId)) {
            throw new RuntimeException("无权访问该评测集");
        }
        return ds;
    }

    /** 加载条目并转为评估服务可用的 LabeledQuery 列表 */
    public List<RetrievalEvalService.LabeledQuery> loadItems(Long datasetId) {
        List<RetrievalEvalService.LabeledQuery> out = new ArrayList<>();
        for (EvalDatasetItem row : itemRepository.findByDatasetId(datasetId)) {
            out.add(new RetrievalEvalService.LabeledQuery(
                    row.getQuery(), parseLongs(row.getRelevantChunkIds()), parseLongs(row.getRelevantDocIds())));
        }
        return out;
    }

    /**
     * 导出为与 {@code POST /api/admin/eval} 入参兼容的 JSON 结构。
     * <p>便于把库内评测集交给现有 PowerShell 脚本或外部工具使用。
     */
    public Map<String, Object> export(Long kbId, Long datasetId) {
        EvalDataset ds = get(kbId, datasetId);
        List<Map<String, Object>> queries = new ArrayList<>();
        for (EvalDatasetItem row : itemRepository.findByDatasetId(datasetId)) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("query", row.getQuery());
            q.put("relevantChunkIds", parseLongs(row.getRelevantChunkIds()));
            q.put("relevantDocIds", parseLongs(row.getRelevantDocIds()));
            queries.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", ds.getKbId());
        out.put("datasetId", ds.getId());
        out.put("name", ds.getName());
        out.put("source", ds.getSource());
        out.put("version", ds.getVersion());
        out.put("count", queries.size());
        out.put("queries", queries);
        return out;
    }

    /** 删除评测集及其条目 */
    @Transactional
    public void delete(Long kbId, Long datasetId) {
        get(kbId, datasetId); // 归属校验
        itemRepository.deleteByDatasetId(datasetId);
        datasetRepository.deleteById(datasetId);
        log.info("评测集已删除：id={}", datasetId);
    }

    /** 某 KB 下可用作标注候选的 chunk 数（自动生成前的容量提示） */
    public long countChunks(Long kbId) {
        return chunkRepository.findByKbId(kbId).size();
    }

    // ===== 工具 =====

    private static String join(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static Set<Long> parseLongs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 跳过非法 id
            }
        }
        return out;
    }
}
