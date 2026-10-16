package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.PromptTemplate;
import com.qoobot.qoorag.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务（RAG-040）。
 * <p>
 * 模板解析优先级：<b>KB 级启用模板 → 全局启用模板 → 内置默认模板</b>。
 * 任一层缺失都向下回退，保证 Chat 链路永不因模板缺失而中断。
 * <p>
 * 渲染：简单的 {@code {{var}}} 占位符替换（不引入完整模板引擎，避免额外依赖）。
 * 内置默认模板与改造前 {@code ChatService} 硬编码的 system prompt <b>文本完全一致</b>，
 * 因此默认行为零变化（回归安全）。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * 内置默认模板（与改造前 ChatService 硬编码文本保持一致，保证默认行为不变）。
     * 变量：{@code {{context}}} 参考资料块、{@code {{query}}} 用户问题。
     */
    public static final String DEFAULT_TEMPLATE = """
            你是一个基于知识库的智能问答助手。请严格根据以下参考资料回答用户问题。

            规则：
            1. 仅使用【参考资料】中的信息作答，不要编造。
            2. 如果参考资料不足以回答，请如实告知"参考资料中未包含相关信息"。
            3. 回答简洁准确；若引用了某条参考资料，请在对应句末用 `[n]` 标注（n 为【参考资料 n】的编号），
               支持一句引用多个来源（如 `结论成立[1][2]`），未引用的句子不要加标记。
            4. 使用中文回答。

            {{context}}
            """;

    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /** 模板解析结果：渲染后的文本 + 来源（BUILTIN / GLOBAL / KB）+ 模板 ID 与版本 */
    public record ResolvedTemplate(String content, String source, Long templateId, Integer version) {}

    /**
     * 解析并渲染模板。
     *
     * @param kbId    知识库 ID（可为 null，此时只用全局/内置模板）
     * @param scene   场景，见 {@link PromptTemplate#SCENE_CHAT}
     * @param context 已组装好的参考资料文本
     * @param query   用户问题
     */
    public ResolvedTemplate resolveAndRender(Long kbId, String scene, String context, String query) {
        String sceneKey = (scene == null || scene.isBlank()) ? PromptTemplate.SCENE_CHAT : scene;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("context", context == null ? "" : context);
        vars.put("query", query == null ? "" : query);

        // 1. KB 级启用模板
        if (kbId != null) {
            Optional<PromptTemplate> kbTpl = repository
                    .findFirstByKbIdAndSceneAndEnabledTrue(kbId, sceneKey);
            if (kbTpl.isPresent()) {
                return render(kbTpl.get(), vars, "KB");
            }
        }
        // 2. 全局启用模板
        Optional<PromptTemplate> global = repository.findFirstByKbIdIsNullAndSceneAndEnabledTrue(sceneKey);
        if (global.isPresent()) {
            return render(global.get(), vars, "GLOBAL");
        }
        // 3. 内置默认模板
        log.debug("未找到启用模板（kbId={}, scene={}），使用内置默认模板", kbId, sceneKey);
        return new ResolvedTemplate(render(DEFAULT_TEMPLATE, vars), "BUILTIN", null, 0);
    }

    /** 取某 KB + 场景的启用模板原文（不渲染），供管理界面预览 */
    public ResolvedTemplate resolveRaw(Long kbId, String scene) {
        String sceneKey = (scene == null || scene.isBlank()) ? PromptTemplate.SCENE_CHAT : scene;
        if (kbId != null) {
            Optional<PromptTemplate> kbTpl = repository.findFirstByKbIdAndSceneAndEnabledTrue(kbId, sceneKey);
            if (kbTpl.isPresent()) {
                return new ResolvedTemplate(kbTpl.get().getContent(), "KB",
                        kbTpl.get().getId(), kbTpl.get().getVersion());
            }
        }
        Optional<PromptTemplate> global = repository.findFirstByKbIdIsNullAndSceneAndEnabledTrue(sceneKey);
        if (global.isPresent()) {
            return new ResolvedTemplate(global.get().getContent(), "GLOBAL",
                    global.get().getId(), global.get().getVersion());
        }
        return new ResolvedTemplate(DEFAULT_TEMPLATE, "BUILTIN", null, 0);
    }

    /** 保存模板；同一 kbId + scene 下启用新模板会自动停用旧模板（单启用约束） */
    @Transactional
    public PromptTemplate save(Long kbId, String scene, String name, String content, boolean enable) {
        String sceneKey = (scene == null || scene.isBlank()) ? PromptTemplate.SCENE_CHAT : scene;
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模板内容不能为空");
        }
        if (!content.contains("{{context}}")) {
            throw new IllegalArgumentException("模板必须包含 {{context}} 占位符（参考资料注入点）");
        }
        int nextVersion = repository.findByKbIdAndSceneOrderByVersionDesc(kbId, sceneKey)
                .stream().mapToInt(PromptTemplate::getVersion).max().orElse(0) + 1;

        PromptTemplate tpl = new PromptTemplate();
        tpl.setKbId(kbId);
        tpl.setScene(sceneKey);
        tpl.setName(name == null || name.isBlank() ? "模板 v" + nextVersion : name);
        tpl.setContent(content);
        tpl.setEnabled(enable);
        tpl.setVersion(nextVersion);
        tpl.setCreatedAt(LocalDateTime.now());
        tpl.setUpdatedAt(LocalDateTime.now());
        PromptTemplate saved = repository.save(tpl);

        if (enable) {
            // 单启用约束：停用同作用域其它模板
            for (PromptTemplate other : repository.findByKbIdAndSceneOrderByVersionDesc(kbId, sceneKey)) {
                if (!other.getId().equals(saved.getId()) && Boolean.TRUE.equals(other.getEnabled())) {
                    other.setEnabled(false);
                    other.setUpdatedAt(LocalDateTime.now());
                    repository.save(other);
                }
            }
            log.info("启用 Prompt 模板 kbId={} scene={} id={} v={}（已停用同作用域旧模板）",
                    kbId, sceneKey, saved.getId(), saved.getVersion());
        }
        return saved;
    }

    private ResolvedTemplate render(PromptTemplate tpl, Map<String, String> vars, String source) {
        return new ResolvedTemplate(render(tpl.getContent(), vars), source, tpl.getId(), tpl.getVersion());
    }

    /** 列出某 KB + 场景的全部模板（版本倒序） */
    public List<PromptTemplate> list(Long kbId, String scene) {
        String sceneKey = normalizeScene(scene);
        return repository.findByKbIdAndSceneOrderByVersionDesc(kbId, sceneKey);
    }

    /** 列出全局模板（版本倒序） */
    public List<PromptTemplate> listGlobal(String scene) {
        String sceneKey = normalizeScene(scene);
        return repository.findByKbIdIsNullAndSceneOrderByVersionDesc(sceneKey);
    }

    /** 启用指定模板，并停用同作用域其它模板（单启用约束） */
    @Transactional
    public PromptTemplate enable(Long id) {
        PromptTemplate tpl = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
        tpl.setEnabled(true);
        tpl.setUpdatedAt(LocalDateTime.now());
        PromptTemplate saved = repository.save(tpl);
        for (PromptTemplate other : repository.findByKbIdAndSceneOrderByVersionDesc(tpl.getKbId(), tpl.getScene())) {
            if (!other.getId().equals(id) && Boolean.TRUE.equals(other.getEnabled())) {
                other.setEnabled(false);
                other.setUpdatedAt(LocalDateTime.now());
                repository.save(other);
            }
        }
        return saved;
    }

    /** 删除模板 */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("模板不存在: " + id);
        }
        repository.deleteById(id);
    }

    private static String normalizeScene(String scene) {
        return (scene == null || scene.isBlank()) ? PromptTemplate.SCENE_CHAT : scene;
    }

    /** {@code {{var}}} 占位符替换；未提供的变量替换为空串 */
    static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
