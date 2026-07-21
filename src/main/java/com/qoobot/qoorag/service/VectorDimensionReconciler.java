package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.ResourcePoolConfig;
import com.qoobot.qoorag.repository.ResourcePoolConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量列维度对账器（#维度自愈）：pgvector 列维度固定，但 embedding 模型可切换
 * （text-embedding-v3/v4 = 1024 维，v1/v2/async-v2 = 1536 维）。
 * 应用启动、资源池配置加载完成后，将 vector_data.embedding 列维度对齐到当前模型维度，
 * 避免“expected N dimensions, not M”入库失败。表为空时自动 ALTER；已有数据则给出明确提示。
 */
@Component
@Order(20)
public class VectorDimensionReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorDimensionReconciler.class);
    private static final String TABLE = "vector_data";
    private static final String COLUMN = "embedding";

    private final BailianConfig bailianConfig;
    private final JdbcTemplate jdbcTemplate;
    private final ResourcePoolConfigRepository repository;

    public VectorDimensionReconciler(BailianConfig bailianConfig,
                                     JdbcTemplate jdbcTemplate,
                                     ResourcePoolConfigRepository repository) {
        this.bailianConfig = bailianConfig;
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int target = resolveTargetDimension();
            Integer current = currentDimension();
            if (current == null) {
                log.warn("未找到 {}.{} 列，跳过维度对账（请确认数据库已按 sql/init.sql 初始化）", TABLE, COLUMN);
                return;
            }
            if (current == target) {
                log.info("向量列维度对账通过：{}.{} = vector({})", TABLE, COLUMN, target);
                return;
            }
            long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
            if (rows == 0) {
                jdbcTemplate.execute("ALTER TABLE " + TABLE + " ALTER COLUMN " + COLUMN
                        + " TYPE vector(" + target + ")");
                log.info("已将 {}.{} 维度由 {} 调整为 {}（表为空，已自动迁移）",
                        TABLE, COLUMN, current, target);
            } else {
                log.error("向量列维度({})与 embedding 模型维度({})不一致，且 {} 已有 {} 条数据，无法自动调整。"
                        + "请先清空 {} 与 chunk 表后重启，或改回维度匹配的 embedding 模型。",
                        current, target, TABLE, rows, TABLE);
            }
        } catch (Exception e) {
            log.warn("向量列维度对账失败（不影响启动，但上传可能因维度不匹配报错）: {}", e.getMessage());
        }
    }

    /** 目标维度：优先 VECTOR.dimension 显式配置，否则由 embedding 模型推导 */
    private int resolveTargetDimension() {
        String dimCfg = repository.findByCategoryAndConfigKey("VECTOR", "dimension")
                .map(ResourcePoolConfig::getConfigValue).orElse(null);
        if (dimCfg != null && !dimCfg.isBlank()) {
            try {
                int d = Integer.parseInt(dimCfg.trim());
                if (d > 0) return d;
            } catch (NumberFormatException ignored) {
                log.warn("VECTOR.dimension 配置非法（{}），改由模型推导", dimCfg);
            }
        }
        return dimensionForModel(bailianConfig.getEmbeddingModel());
    }

    /** 百炼文本向量模型维度映射；未知模型默认 1024 */
    static int dimensionForModel(String model) {
        if (model == null || model.isBlank()) return 1024;
        String m = model.toLowerCase();
        if (m.contains("v3") || m.contains("v4")) return 1024;
        if (m.contains("v1") || m.contains("v2") || m.contains("async")) return 1536;
        return 1024;
    }

    /** 读取 vector_data.embedding 当前列维度；无维度(vector 无参)返回 -1；列不存在返回 null */
    private Integer currentDimension() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.atttypmod FROM pg_attribute a "
                            + "JOIN pg_class c ON c.oid = a.attrelid "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE c.relname = ? AND a.attname = ? AND NOT a.attisdropped",
                    (rs, rn) -> rs.getInt(1),
                    TABLE, COLUMN);
        } catch (Exception e) {
            log.debug("读取列维度失败: {}", e.getMessage());
            return null;
        }
    }
}
