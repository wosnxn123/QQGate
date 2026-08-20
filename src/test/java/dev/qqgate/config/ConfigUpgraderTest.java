package dev.qqgate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigUpgrader 场景测试（snakeyaml 直读，避免依赖 Bukkit API）。
 */
class ConfigUpgraderTest {

    @TempDir
    Path dir;

    private static final String TEMPLATE = """
            config-version: 2
            onebot:
              mode: reverse-ws
              listen-port: 6700
              access-token: ""
            groups:
              allowed: [123456789]
            admins:
              qq: []
              respond:
                group: true
                private: true
            bind:
              code-length: 4
              self-unbind: false
            """;

    @Test
    void freshInstallIsNoop() throws Exception {
        Path f = dir.resolve("config.yml"); // 不存在
        var r = ConfigUpgrader.upgradeIfNeeded(f, TEMPLATE);
        assertFalse(r.upgraded());
        assertEquals(2, r.toVersion());
        assertFalse(Files.exists(f)); // 不写文件，交给 saveDefaultConfig
    }

    @Test
    void upgradesAndAddsMissingSections() throws Exception {
        Path f = dir.resolve("config.yml");
        // v1 旧配置：无 admins 段、无 config-version
        Files.writeString(f, """
                onebot:
                  mode: reverse-ws
                  listen-port: 6700
                groups:
                  allowed: [111]
                bind:
                  code-length: 4
                """);

        var r = ConfigUpgrader.upgradeIfNeeded(f, TEMPLATE);
        assertTrue(r.upgraded());
        assertEquals(1, r.fromVersion());
        assertEquals(2, r.toVersion());
        assertTrue(r.addedKeys() > 0);
        assertNotNull(r.backup());

        String merged = Files.readString(f);
        // 新段补齐
        assertTrue(merged.contains("admins:"), merged);
        assertTrue(merged.contains("self-unbind"), merged);
        assertTrue(merged.contains("access-token"), merged);
        // 版本号已对齐
        assertTrue(merged.contains("config-version: 2"));
    }

    @Test
    void userValuesPreserved() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                onebot:
                  mode: forward-ws
                  listen-port: 7777
                  access-token: "my-secret"
                groups:
                  allowed: [999888]
                bind:
                  code-length: 6
                  self-unbind: true
                """);

        ConfigUpgrader.upgradeIfNeeded(f, TEMPLATE);

        String merged = Files.readString(f);
        assertTrue(merged.contains("mode: forward-ws"), merged);
        assertTrue(merged.contains("listen-port: 7777"), merged);
        assertTrue(merged.contains("my-secret"), merged);
        assertTrue(merged.contains("999888"), merged);
        assertTrue(merged.contains("code-length: 6"), merged);
        assertTrue(merged.contains("self-unbind: true"), merged);
    }

    @Test
    void backupCreatedBeforeWrite() throws Exception {
        Path f = dir.resolve("config.yml");
        String original = """
                onebot:
                  mode: reverse-ws
                """;
        Files.writeString(f, original);

        var r = ConfigUpgrader.upgradeIfNeeded(f, TEMPLATE);
        assertTrue(r.upgraded());

        Path backup = dir.resolve(r.backup());
        assertTrue(Files.exists(backup), "backup file must exist");
        assertEquals(original, Files.readString(backup), "backup must be byte-identical to original");
    }

    @Test
    void idempotentWhenAlreadyCurrent() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 2
                onebot:
                  mode: reverse-ws
                """);

        var r = ConfigUpgrader.upgradeIfNeeded(f, TEMPLATE);
        assertFalse(r.upgraded());
        // 无备份产生（文件未被改写）
        assertEquals(0, (int) java.util.stream.Stream.of(Files.list(dir).toArray())
                .filter(p -> p.toString().contains(".bak-")).count());
    }

    @Test
    void v7MigratesStockBanTextsToAddReason() throws Exception {
        // v6 → v7：旧默认封禁文案补 {reason} 占位
        String tpl = """
                config-version: 7
                kick:
                  banned-message: "<red><b>该账号已被封禁</b></red>\\n<gray>原因：账号绑定的QQ已被服务器拉黑{reason}\\n如有异议请联系管理员申诉</gray>"
                  name-banned-message: "<red><b>该名称已被封禁</b></red>\\n<gray>原因：此名称的历史账号曾绑定被拉黑的QQ{reason}\\n如你是新玩家且首次使用此名称，请联系管理员处理</gray>"
                messages:
                  qq-banned: "{at} 该QQ已被服务器拉黑{reason}，无法绑定；如有异议请联系管理员"
                """;
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 6
                kick:
                  banned-message: "<red><b>该账号已被封禁</b></red>\\n<gray>原因：账号绑定的QQ已被服务器拉黑\\n如有异议请联系管理员申诉</gray>"
                  name-banned-message: "<red><b>该名称已被封禁</b></red>\\n<gray>原因：此名称的历史账号曾绑定被拉黑的QQ\\n如你是新玩家且首次使用此名称，请联系管理员处理</gray>"
                messages:
                  qq-banned: "{at} 该QQ已被服务器拉黑，无法绑定；如有异议请联系管理员"
                """);

        var r = ConfigUpgrader.upgradeIfNeeded(f, tpl);
        assertTrue(r.upgraded());
        assertEquals(7, r.toVersion());

        @SuppressWarnings("unchecked")
        Map<String, Object> back = new Yaml().load(Files.readString(f));
        @SuppressWarnings("unchecked")
        Map<String, Object> kick = (Map<String, Object>) back.get("kick");
        @SuppressWarnings("unchecked")
        Map<String, Object> messages = (Map<String, Object>) back.get("messages");
        assertTrue(((String) kick.get("banned-message")).contains("拉黑{reason}"), back.toString());
        assertTrue(((String) kick.get("name-banned-message")).contains("QQ{reason}"), back.toString());
        assertTrue(((String) messages.get("qq-banned")).contains("拉黑{reason}"), back.toString());
    }

    @Test
    void v7KeepsCustomizedBanTextUntouched() throws Exception {
        String tpl = """
                config-version: 7
                kick:
                  banned-message: "<red><b>该账号已被封禁</b></red>\\n<gray>原因：账号绑定的QQ已被服务器拉黑{reason}\\n如有异议请联系管理员申诉</gray>"
                """;
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 6
                kick:
                  banned-message: "自定义封禁文案"
                """);

        ConfigUpgrader.upgradeIfNeeded(f, tpl);

        @SuppressWarnings("unchecked")
        Map<String, Object> back = new Yaml().load(Files.readString(f));
        @SuppressWarnings("unchecked")
        Map<String, Object> kick = (Map<String, Object>) back.get("kick");
        assertEquals("自定义封禁文案", kick.get("banned-message"));
    }
}
