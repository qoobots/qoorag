package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Prompt 模板（RAG-040）。
 * <p>
 * 模板从 Java 硬编码抽离到数据库，支持按知识库 / 场景绑定、版本化与灰度，
 * 修改模板无需发版。表由 {@code RagSchemaMigrator} 幂等创建。
 * <p>
 * 变量占位符：{@code {{context}}}（上下文）、{@code {{query}}}（用户问题）、
 * {@code {{date}}}（当前日期）。渲染见 {@code PromptTemplateService.render}。
 */
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {

    /** 默认场景：RAG 问答 */
    public static final String SCENE_CHAT = "chat";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的知识库；null 表示全局默认模板 */
    @Column(name = "kb_id")
    private Long kbId;

    /** 场景，默认 chat */
    @Column(length = 64, nullable = false)
    private String scene = SCENE_CHAT;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 是否启用；同一 kbId + scene 下至多一条启用（由服务层保证） */
    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
