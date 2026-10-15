package com.qoobot.qoorag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * .env 解析器单测（密钥整理配套）。
 * <p>解析器是纯函数，不依赖 Spring 容器，可直接运行。
 */
class DotenvEnvironmentPostProcessorTest {

    private final DotenvEnvironmentPostProcessor processor =
            new DotenvEnvironmentPostProcessor();

    private Path write(String content) throws Exception {
        Path f = Files.createTempFile("dotenv-test", ".env");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Nested
    @DisplayName("基本解析")
    class Basic {

        @Test
        @DisplayName("标准 KEY=VALUE")
        void simple() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=value\n"));
            assertEquals("value", out.get("KEY"));
        }

        @Test
        @DisplayName("值含等号时只按第一个 = 切分")
        void value_with_equals() throws Exception {
            Map<String, Object> out = processor.parse(write("URL=https://x.com/a?b=1&c=2\n"));
            assertEquals("https://x.com/a?b=1&c=2", out.get("URL"));
        }

        @Test
        @DisplayName("键值两侧空白被裁剪")
        void trims_whitespace() throws Exception {
            Map<String, Object> out = processor.parse(write("  KEY  =  value  \n"));
            assertEquals("value", out.get("KEY"));
        }

        @Test
        @DisplayName("export 前缀被去掉")
        void export_prefix() throws Exception {
            Map<String, Object> out = processor.parse(write("export KEY=value\n"));
            assertEquals("value", out.get("KEY"));
        }

        @Test
        @DisplayName("空值被保留（用于显式清空某个配置）")
        void empty_value_kept() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=\n"));
            assertTrue(out.containsKey("KEY"), "空值键必须存在");
            assertEquals("", out.get("KEY"));
        }

        @Test
        @DisplayName("多个键按出现顺序保留")
        void preserves_order() throws Exception {
            Map<String, Object> out = processor.parse(write("A=1\nB=2\nC=3\n"));
            assertEquals(java.util.List.of("A", "B", "C"), java.util.List.copyOf(out.keySet()));
        }

        @Test
        @DisplayName("后写的同名键不覆盖先写的（dotenv 惯例）")
        void first_wins() throws Exception {
            Map<String, Object> out = processor.parse(write("K=first\nK=second\n"));
            assertEquals("first", out.get("K"));
        }
    }

    @Nested
    @DisplayName("注释与空行")
    class Comments {

        @Test
        @DisplayName("整行注释被忽略")
        void full_line_comment() throws Exception {
            Map<String, Object> out = processor.parse(write("# 这是注释\nKEY=value\n"));
            assertEquals(1, out.size());
            assertFalse(out.containsKey("# 这是注释"));
        }

        @Test
        @DisplayName("行内注释被去掉")
        void inline_comment() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=value  # 这是注释\n"));
            assertEquals("value", out.get("KEY"));
        }

        @Test
        @DisplayName("引号内的 # 不当注释")
        void hash_inside_quotes() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=\"va#lue\"\n"));
            assertEquals("va#lue", out.get("KEY"));
        }

        @Test
        @DisplayName("空行与纯空白行被忽略")
        void blank_lines() throws Exception {
            Map<String, Object> out = processor.parse(write("\n   \n\t\nKEY=value\n\n"));
            assertEquals(1, out.size());
        }
    }

    @Nested
    @DisplayName("引号")
    class Quotes {

        @Test
        @DisplayName("双引号被剥离")
        void double_quotes() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=\"some value\"\n"));
            assertEquals("some value", out.get("KEY"));
        }

        @Test
        @DisplayName("单引号被剥离")
        void single_quotes() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY='some value'\n"));
            assertEquals("some value", out.get("KEY"));
        }

        @Test
        @DisplayName("引号内的空格与等号被保留")
        void quoted_specials() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=\"a b = c\"\n"));
            assertEquals("a b = c", out.get("KEY"));
        }

        @Test
        @DisplayName("引号不匹配时按原样返回")
        void mismatched_quotes() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=\"unclosed\n"));
            assertEquals("\"unclosed", out.get("KEY"));
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("无 = 的行被忽略")
        void no_equals_ignored() throws Exception {
            Map<String, Object> out = processor.parse(write("这一行没有等号\nKEY=value\n"));
            assertEquals(1, out.size());
        }

        @Test
        @DisplayName("以 = 开头的行被忽略")
        void leading_equals_ignored() throws Exception {
            Map<String, Object> out = processor.parse(write("=value\nKEY=value\n"));
            assertEquals(1, out.size());
        }

        @Test
        @DisplayName("非法键名被忽略")
        void invalid_key_ignored() throws Exception {
            Map<String, Object> out = processor.parse(write("MY-KEY=value\n9KEY=value\nKEY=value\n"));
            assertEquals(1, out.size(), "含连字符与数字开头的键名应被忽略");
            assertEquals("value", out.get("KEY"));
        }

        @Test
        @DisplayName("空文件返回空 Map")
        void empty_file() throws Exception {
            assertTrue(processor.parse(write("")).isEmpty());
        }

        @Test
        @DisplayName("CRLF 行尾正常处理（Windows 编辑器常见）")
        void crlf() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=value\r\nA=1\r\n"));
            assertEquals("value", out.get("KEY"));
            assertEquals("1", out.get("A"));
        }

        @Test
        @DisplayName("UTF-8 中文值正确读取")
        void utf8_values() throws Exception {
            Map<String, Object> out = processor.parse(write("KEY=中文值\n"));
            assertEquals("中文值", out.get("KEY"));
        }
    }
}
