package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG-018：MMR（最大边际相关）多样性重排。
 * <p>
 * 在精排（rerank）之后、上下文组装之前对片段做去冗余：优先保留「高相关且彼此不相似」的片段，
 * 缓解 hybrid 检索把大量近似片段塞进上下文、挤占 token 且引入噪声的问题。
 * 相关性取片段 score，片段间相似度用字符二元组 Jaccard 近似（无需回查向量，零额外 IO）。
 * {@code lambda} 权衡相关性与多样性：越大越偏相关，越小越偏多样。
 */
@Service
public class MmrService {

    private static final Logger log = LoggerFactory.getLogger(MmrService.class);

    @Value("${qoorag.retrieval.mmr-enabled:false}")
    private boolean enabled;
    @Value("${qoorag.retrieval.mmr-lambda:0.5}")
    private double lambda;

    public List<RetrieveChunk> mmr(String query, List<RetrieveChunk> chunks) {
        if (!enabled || chunks == null || chunks.size() <= 1) {
            return chunks;
        }
        int n = chunks.size();
        List<Set<String>> tokens = new ArrayList<>(n);
        for (RetrieveChunk c : chunks) {
            tokens.add(tokenSet(c.getContent()));
        }
        double maxScore = chunks.stream().mapToDouble(RetrieveChunk::getScore).max().orElse(1.0);
        if (maxScore <= 0) {
            maxScore = 1.0;
        }

        boolean[] selected = new boolean[n];
        List<RetrieveChunk> result = new ArrayList<>(n);

        // 第一轮：选相关性最高的片段
        int first = 0;
        double bestRel = -1;
        for (int i = 0; i < n; i++) {
            double rel = chunks.get(i).getScore() / maxScore;
            if (rel > bestRel) {
                bestRel = rel;
                first = i;
            }
        }
        selected[first] = true;
        result.add(chunks.get(first));

        // 后续轮：贪心选 MMR 最大的片段
        while (result.size() < n) {
            int pick = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (selected[i]) {
                    continue;
                }
                double rel = chunks.get(i).getScore() / maxScore;
                double maxSim = 0.0;
                for (int j = 0; j < n; j++) {
                    if (selected[j]) {
                        double sim = jaccard(tokens.get(i), tokens.get(j));
                        if (sim > maxSim) {
                            maxSim = sim;
                        }
                    }
                }
                double mmr = lambda * rel - (1 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    pick = i;
                }
            }
            if (pick < 0) {
                break;
            }
            selected[pick] = true;
            result.add(chunks.get(pick));
        }
        log.info("MMR 多样性重排完成：{} 个片段，lambda={}", n, lambda);
        return result;
    }

    /** 片段 token 集合：中文按字符二元组、统一小写，作为轻量相似度特征 */
    private static Set<String> tokenSet(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return set;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 1 < lower.length(); i++) {
            set.add(lower.substring(i, i + 2));
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String s : a) {
            if (b.contains(s)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }
}
