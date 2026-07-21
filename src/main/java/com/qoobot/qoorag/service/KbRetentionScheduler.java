package com.qoobot.qoorag.service;

import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 知识库保留期定时清理（4.11）：软删除超过保留期的知识库物理清理业务数据 */
@Component
public class KbRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KbRetentionScheduler.class);

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 软删除保留期（天），取自 qoorag.security.kb-retention-days */
    @Value("${qoorag.security.kb-retention-days:30}")
    private int retentionDays;

    public KbRetentionScheduler(KnowledgeBaseRepository kbRepository,
                               KnowledgeBaseService knowledgeBaseService) {
        this.kbRepository = kbRepository;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 每日 03:00 执行（低峰）；扫描超期软删除知识库并物理清理 */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Long> expired = kbRepository.findByDeletedAtIsNotNullAndDeletedAtBefore(cutoff)
                .stream()
                .map(kb -> kb.getId())
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        log.info("保留期清理：发现 {} 个超期知识库，开始物理清理", expired.size());
        for (Long kbId : expired) {
            try {
                knowledgeBaseService.purge(kbId);
                log.info("保留期清理：知识库 {} 已物理清理", kbId);
            } catch (Exception e) {
                log.error("保留期清理：知识库 {} 清理失败: {}", kbId, e.getMessage(), e);
            }
        }
    }
}
