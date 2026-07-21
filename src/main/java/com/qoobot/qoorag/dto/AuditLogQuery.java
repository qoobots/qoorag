package com.qoobot.qoorag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;

/** 审计日志查询参数（租户隔离由 SecurityContext 注入，不在此传输） */
public class AuditLogQuery {

    private String action;        // 操作类型过滤（可选）
    private String objectType;    // 对象类型过滤（可选）
    private LocalDateTime start;  // 起始时间（可选，含）
    private LocalDateTime end;    // 结束时间（可选，含）

    @Min(0)
    private int page = 0;         // 页码（从 0 开始）

    @Min(1)
    @Max(200)
    private int size = 20;        // 每页条数

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public LocalDateTime getStart() { return start; }
    public void setStart(LocalDateTime start) { this.start = start; }
    public LocalDateTime getEnd() { return end; }
    public void setEnd(LocalDateTime end) { this.end = end; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}
