package com.qoobot.qoorag.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `.env` 文件加载器（密钥整理配套）。
 *
 * <p><b>为什么需要它</b>：Spring Boot <b>不会自动读取</b> {@code .env}——它只读 OS 环境变量、
 * {@code application.yml} 与命令行参数。改造前项目里的 {@code .env} 放着 API Key 却从未被读取，
 * 等于一个死文件；所有密钥实际上要手工设成 OS 环境变量才能生效。
 *
 * <p>本处理器在应用上下文刷新前把 {@code .env} 的键值注入 Spring 环境：
 * <ul>
 *   <li><b>OS 环境变量优先</b>：{@code .env} 以最低优先级追加（{@code addLast}），
 *       因此生产/CI 中用真正的环境变量即可覆盖，无需改文件；</li>
 *   <li><b>不覆盖已存在的系统属性</b>：避免与 {@code -D} 参数冲突；</li>
 *   <li><b>local profile 也加载</b>：本地开发最常见的入口，不应因 profile 名不同而失效。</li>
 * </ul>
 *
 * <p><b>安全</b>：{@code .env} 已在 {@code .gitignore} 中，本类只在<b>本地磁盘</b>读取它；
 * 仓库内只提交 {@code .env.example} 模板（占位符，无真实密钥）。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 尽量早执行，早于 application.yml 的属性解析 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final String PROPERTY_SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = locate();
        if (dotenv == null) {
            return;
        }
        try {
            Map<String, Object> parsed = parse(dotenv);
            if (parsed.isEmpty()) {
                return;
            }
            // OS 环境变量 / -D 系统属性已存在则不注入，保证外部注入优先
            Map<String, Object> injectable = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (!environment.containsProperty(e.getKey())) {
                    injectable.put(e.getKey(), e.getValue());
                }
            }
            if (injectable.isEmpty()) {
                return;
            }
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, injectable));
            System.out.println("[dotenv] 已加载 " + dotenv.toAbsolutePath()
                    + "（" + injectable.size() + " 项；同名 OS 环境变量优先）");
        } catch (Exception e) {
            // 密钥加载失败不应阻断启动：回退到 OS 环境变量 / application.yml 默认值
            System.err.println("[dotenv] 加载 " + dotenv.toAbsolutePath() + " 失败，已忽略: " + e.getMessage());
        }
    }

    /**
     * 定位 {@code .env}：依次尝试工作目录、类路径（jar）所在目录。
     * <p>{@code mvn spring-boot:run} 与 IDE 运行落在工作目录；
     * {@code java -jar /opt/qoorag/qoorag.jar} 部署时落在 jar 同级目录。
     * 任何一步异常都不应阻断启动——返回 null 即表示"无 .env，用默认配置"。
     */
    static Path locate() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path codeDir = codeLocation();
        Path[] candidates = codeDir == null
                ? new Path[]{cwd.resolve(".env")}
                : new Path[]{
                        cwd.resolve(".env"),
                        // java -jar：codeDir 即 jar 文件，parent 为部署目录
                        // mvn spring-boot:run：codeDir 为 target/classes，parent 为工程根
                        codeDir.toAbsolutePath().normalize().getParent().resolve(".env"),
                };
        for (Path p : candidates) {
            try {
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (Exception ignored) {
                // 单个候选不可访问时继续尝试下一个
            }
        }
        return null;
    }

    /**
     * 当前类所在的代码位置（jar 文件或 classes 目录）。
     * <p>Windows 上 {@code URL.getPath()} 会返回 {@code /D:/...} 这种带前导斜杠的形式，
     * 直接喂给 {@code Paths.get} 会抛 {@code InvalidPathException}；必须经 {@code toURI()} 转换。
     */
    private static Path codeLocation() {
        try {
            java.net.URL location = DotenvEnvironmentPostProcessor.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            try {
                return Paths.get(location.toURI());
            } catch (Exception e) {
                // 非 file: 协议（如 jar:nested:）时退回字符串解析，失败则放弃
                try {
                    return Paths.get(location.getPath());
                } catch (Exception ignored) {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 {@code .env}。
     * <p>支持：{@code KEY=VALUE}、{@code export KEY=VALUE}、{@code #} 注释、空行、
     * 单/双引号包裹的值。不支持 {@code ${VAR}} 展开与多行值——保持简单可预期，
     * 需要展开的场合请用 OS 环境变量或 shell。
     */
    static Map<String, Object> parse(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = stripComment(line).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).trim();
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue; // 无 = 或以 = 开头：非法行，忽略
                }
                String key = trimmed.substring(0, eq).trim();
                String value = unquote(trimmed.substring(eq + 1).trim());
                if (!isValidKey(key)) {
                    System.err.println("[dotenv] 第 " + lineNo + " 行键名非法，已忽略: " + key);
                    continue;
                }
                // 首次出现者生效（与 dotenv 惯例一致：后写不覆盖先写）
                out.putIfAbsent(key, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 去掉行内注释（引号内的 # 不视为注释） */
    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 去掉成对的单/双引号；引号不匹配时按原样返回 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 环境变量名规范：字母/下划线开头，仅含字母数字下划线 */
    private static boolean isValidKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return !Character.isDigit(key.charAt(0));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
