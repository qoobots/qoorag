package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    /** 查某 KB + 场景下已启用的模板 */
    Optional<PromptTemplate> findFirstByKbIdAndSceneAndEnabledTrue(Long kbId, String scene);

    /** 查全局（kbId 为空）已启用的模板 */
    Optional<PromptTemplate> findFirstByKbIdIsNullAndSceneAndEnabledTrue(String scene);

    /** 列出某 KB + 场景的全部模板（按版本倒序） */
    List<PromptTemplate> findByKbIdAndSceneOrderByVersionDesc(Long kbId, String scene);

    /** 列出全局模板 */
    List<PromptTemplate> findByKbIdIsNullAndSceneOrderByVersionDesc(String scene);
}
