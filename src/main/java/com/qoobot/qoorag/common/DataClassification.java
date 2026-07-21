package com.qoobot.qoorag.common;

import java.util.Arrays;
import java.util.List;

/**
 * 数据分级枚举（对齐企业数据分类分级通用标准，#17）。
 * <p>
 * 参考 GB/T 37988《数据安全能力成熟度模型》与《数据安全法》一般·重要·核心 三级映射，
 * 抽象为五档：公开 / 内部 / 受限 / 机密 / 绝密。
 * {@code rank} 越大敏感度越高，供脱敏、访问控制、留存策略等做分级判断。
 */
public enum DataClassification {
    PUBLIC("PUBLIC", "公开", 1),
    INTERNAL("INTERNAL", "内部", 2),
    RESTRICTED("RESTRICTED", "受限", 3),
    CONFIDENTIAL("CONFIDENTIAL", "机密", 4),
    SECRET("SECRET", "绝密", 5);

    public final String code;
    public final String label;
    public final int rank;

    DataClassification(String code, String label, int rank) {
        this.code = code;
        this.label = label;
        this.rank = rank;
    }

    /** 按 code 解析枚举（大小写不敏感、去空白）；非法或空值抛 40001 */
    public static DataClassification fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据分级不能为空");
        }
        for (DataClassification d : values()) {
            if (d.code.equalsIgnoreCase(code.trim())) {
                return d;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "数据分级非法: " + code);
    }

    /** 全量枚举（供管理接口暴露标准清单，为资源池 UI 预留） */
    public static List<DataClassification> listAll() {
        return Arrays.asList(values());
    }

    /** 当前级别敏感度是否不低于 other（rank >= other.rank） */
    public boolean atLeast(DataClassification other) {
        return this.rank >= other.rank;
    }
}
