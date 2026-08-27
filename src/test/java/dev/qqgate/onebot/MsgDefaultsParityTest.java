package dev.qqgate.onebot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * config.yml 的 messages 段与 {@link QqMsg} 默认文案的一致性校验。
 *
 * <p>为什么存在：本次重构前，config.yml 与代码默认文案已经漂移，用户配置里带着
 * {reason_part} 这类代码从不替换的死占位符却无人察觉。本测试把"键集合相等、
 * 默认文案逐字符相等、无 {result} 残留"三件事钉死，任何一方再漂移立刻可见。
 *
 * <p>YAML 解析复用 snakeyaml：build.gradle.kts 已声明
 * {@code testImplementation("org.yaml:snakeyaml:2.2")}（ConfigUpgrader 本身依赖它），
 * 不为此测试新引入任何依赖。
 */
class MsgDefaultsParityTest {

    private static final String REL_PATH = "src/main/resources/config.yml";

    @Test
    void keySetEqualsQqMsgPaths() throws Exception {
        Map<String, Object> yml = loadMessages();

        TreeSet<String> ymlKeys = new TreeSet<>(yml.keySet());
        TreeSet<String> enumKeys = new TreeSet<>();
        for (QqMsg m : QqMsg.values()) {
            assertTrue(m.path().startsWith("messages."),
                    () -> m.name() + " 的配置路径不在 messages 命名空间: " + m.path());
            enumKeys.add(leafKey(m));
        }

        TreeSet<String> missingInYml = new TreeSet<>(enumKeys);
        missingInYml.removeAll(ymlKeys);
        TreeSet<String> extraInYml = new TreeSet<>(ymlKeys);
        extraInYml.removeAll(enumKeys);

        assertTrue(missingInYml.isEmpty() && extraInYml.isEmpty(),
                () -> "messages 键集合漂移：config.yml 缺少 " + missingInYml + "，多出 " + extraInYml);
    }

    @Test
    void defaultsMatchCharForChar() throws Exception {
        Map<String, Object> yml = loadMessages();

        for (QqMsg m : QqMsg.values()) {
            Object actual = yml.get(leafKey(m));
            assertEquals(m.def(), actual, () -> "默认文案不一致 [" + m.path() + "]\n"
                    + "  QqMsg.def() : <" + m.def() + ">\n"
                    + "  config.yml  : <" + actual + ">");
        }
    }

    @Test
    void noResultPlaceholderLeft() throws Exception {
        Map<String, Object> yml = loadMessages();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> e : yml.entrySet()) {
            if (e.getValue() instanceof String s && s.contains("{result}")) {
                offenders.add(e.getKey());
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "messages 段残留 {result}（v9 起无该字段，用户会看到裸花括号）: " + offenders);
    }

    /** 解析 config.yml 并取出 messages 段。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMessages() throws Exception {
        Object rootObj = new Yaml().load(readConfigYml());
        assertInstanceOf(Map.class, rootObj, "config.yml 顶层不是映射");
        Object messages = ((Map<String, Object>) rootObj).get("messages");
        assertInstanceOf(Map.class, messages, "config.yml 缺少 messages 段");
        return (Map<String, Object>) messages;
    }

    /**
     * 文件系统优先（gradle 测试工作目录为项目根，向上兜底几层以防万一），
     * 找不到再退回 classpath（主资源会进测试运行时 classpath）。不依赖 Bukkit。
     */
    private static String readConfigYml() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(REL_PATH);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        try (InputStream in = MsgDefaultsParityTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "找不到 config.yml：既不在项目目录 " + REL_PATH + "，也不在 classpath 根");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** messages.<leaf> → <leaf>。 */
    private static String leafKey(QqMsg m) {
        return m.path().substring("messages.".length());
    }
}
