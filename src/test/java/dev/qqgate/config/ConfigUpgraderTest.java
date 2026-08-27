package dev.qqgate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    // ============================ v8 -> v9 ============================

    /**
     * v9 模板骨架：版本号 9，messages 含 16 个新键与相关保留键、无 4 个退役键
     * （{result} 单键时代遗物）。kick 段保留供多级跳用例复用。
     */
    private static final String V9_TEMPLATE = """
            config-version: 9
            onebot:
              mode: reverse-ws
              listen-port: 6700
            kick:
              banned-message: "<red><b>该账号已被封禁</b></red>\\n<gray>原因：账号绑定的QQ已被服务器拉黑{reason}\\n如有异议请联系管理员申诉</gray>"
            messages:
              bound: "{at} 绑定成功！\\n游戏账号：{player}\\n该QQ当前已绑定 {count}/{max} 个账号，还可绑定 {remaining} 个\\n现在可以重新进入服务器了"
              qqban-ok: "{at} 已拉黑 QQ {qq}{reason}"
              qqban-ok-locked: "名下账号已封锁（绑定保留作案底）：{names}\\n同名新连接将被拒绝；解拉黑后自动复原"
              qqbans-list-header: "{at} QQ 黑名单（{count} 条）："
              qqbans-list-item: "  {qq} · {time}{reason}{names}"
              self-unbind-list-header: "{at} 已绑定 {count}/{max} 个账号："
              self-unbind-list-item: " {index}. {player}（{time}）"
              self-unbind-list-hint: "解绑指定账号：解绑 <账号名>"
              admin-lookup-banned-note: "⚠ 该QQ已被拉黑{reason}"
              admin-lookup-qq-empty: "{at} QQ {qq} 未绑定任何账号"
              admin-lookup-qq-header: "{at} QQ {qq} 绑定 {count} 个账号："
              admin-lookup-qq-item: "  {player} · {time}"
              admin-lookup-player-header: "{at} 玩家 {player} 绑定 {count} 个QQ："
              admin-lookup-player-item: "  QQ {qq} · {time}"
              admin-lookup-empty: "{at} 玩家 {target} 未绑定QQ"
              admin-unbind-notfound: "{at} {target} 无绑定"
              admin-unbind-notfound-exact: "{at} 未找到 {player} 与 QQ {qq} 的绑定"
              admin-unbind-ambiguous-header: "{at} {target} 名下有 {count} 条绑定："
              admin-unbind-ambiguous-item: " {index}. QQ {qq}（{player}）"
              admin-unbind-ambiguous-hint: "精确解绑：解绑 <玩家名> <QQ号>\\n清空全部：全解绑 <目标>"
              admin-status: "{at} mode={mode} connected={connected} self_id={self_id} binds={binds} active_codes={codes}"
            """;

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }

    /** 捕获 ConfigUpgrader 经 JUL（与插件同名 logger "QQGate"）打出的全部 warning 文案。 */
    private static List<String> captureWarnings(ThrowingRun action) throws Exception {
        Logger log = Logger.getLogger("QQGate");
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        boolean useParent = log.getUseParentHandlers();
        log.setUseParentHandlers(false); // 测试期间不向控制台重复打印
        log.addHandler(handler);
        try {
            action.run();
        } finally {
            log.removeHandler(handler);
            log.setUseParentHandlers(useParent);
        }
        return warnings;
    }

    /** 升级后回读 messages 段。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> messagesOf(Path f) throws Exception {
        Map<String, Object> back = new Yaml().load(Files.readString(f));
        return (Map<String, Object>) back.get("messages");
    }

    @Test
    void v9RemovesCustomizedRetiredKeysWithNamedWarnings() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 8
                messages:
                  self-unbind-list: "自定义列表 {result}"
                  qqbans-list: "自定义黑名单 {result}"
                  admin-lookup: "自定义查询 {result}"
                  admin-unbind-ambiguous: "自定义多选 {result}"
                """);

        List<String> warnings = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));

        Map<String, Object> messages = messagesOf(f);
        for (String key : List.of("self-unbind-list", "qqbans-list", "admin-lookup", "admin-unbind-ambiguous")) {
            assertFalse(messages.containsKey(key), "退役键应被移除: " + key + "\n" + messages);
        }
        assertEquals(4, warnings.size(), () -> "每个自定义退役键各一条告警: " + warnings);
        assertTrue(warnings.get(0).contains("messages.self-unbind-list"), warnings.toString());
        assertTrue(warnings.get(0).contains("messages.self-unbind-list-header"), warnings.toString());
        assertTrue(warnings.get(1).contains("messages.qqbans-list"), warnings.toString());
        assertTrue(warnings.get(1).contains("messages.qqbans-list-item"), warnings.toString());
        assertTrue(warnings.get(2).contains("messages.admin-lookup"), warnings.toString());
        assertTrue(warnings.get(2).contains("messages.admin-lookup-qq-header"), warnings.toString());
        assertTrue(warnings.get(3).contains("messages.admin-unbind-ambiguous"), warnings.toString());
        assertTrue(warnings.get(3).contains("messages.admin-unbind-ambiguous-hint"), warnings.toString());
    }

    @Test
    void v9RemovesStockRetiredKeysSilentlyAndAddsNewKeys() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 8
                messages:
                  self-unbind-list: "{at} {result}"
                  qqbans-list: "{at} {result}"
                  admin-lookup: "{at} {result}"
                  admin-unbind-ambiguous: "{at} {result}"
                """);

        List<String> warnings = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));
        assertTrue(warnings.isEmpty(), "旧默认值应静默移除: " + warnings);

        Map<String, Object> messages = messagesOf(f);
        for (String key : List.of("self-unbind-list", "qqbans-list", "admin-lookup", "admin-unbind-ambiguous")) {
            assertFalse(messages.containsKey(key), "退役键应被移除: " + key);
        }
        // 16 个新键由 deepMerge 补默认值
        for (String key : List.of("self-unbind-list-header", "self-unbind-list-item", "self-unbind-list-hint",
                "qqban-ok-locked", "qqbans-list-header", "qqbans-list-item",
                "admin-lookup-banned-note", "admin-lookup-qq-empty", "admin-lookup-qq-header",
                "admin-lookup-qq-item", "admin-lookup-player-header", "admin-lookup-player-item",
                "admin-unbind-notfound-exact", "admin-unbind-ambiguous-header",
                "admin-unbind-ambiguous-item", "admin-unbind-ambiguous-hint")) {
            assertTrue(messages.containsKey(key), "新键缺失: " + key);
        }
    }

    @Test
    void v9PreservesUnrelatedCustomValuesAndBumpsVersion() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 8
                onebot:
                  listen-port: 7777
                messages:
                  bound: "自定义绑定文案"
                  self-unbind-list: "{at} {result}"
                """);

        ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE);

        @SuppressWarnings("unchecked")
        Map<String, Object> back = new Yaml().load(Files.readString(f));
        assertEquals(9, ((Number) back.get("config-version")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> onebot = (Map<String, Object>) back.get("onebot");
        assertEquals(7777, ((Number) onebot.get("listen-port")).intValue());
        assertEquals("自定义绑定文案", messagesOf(f).get("bound"));
    }

    @Test
    void v9MigratesStockSemanticRepairDefaults() throws Exception {
        Path f = dir.resolve("config.yml");
        // v8 旧默认：qqban-ok/admin-status 仍是 {result}；lookup-empty/unbind-notfound 为旧措辞
        Files.writeString(f, """
                config-version: 8
                messages:
                  qqban-ok: "{at} {result}"
                  admin-status: "{at} {result}"
                  admin-lookup-empty: "{at} {target} 未找到绑定"
                  admin-unbind-notfound: "{at} {target} 无绑定（或未找到该组合）"
                """);

        List<String> warnings = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));
        assertTrue(warnings.isEmpty(), "语义修复键是保留键，不该触发退役告警: " + warnings);

        Map<String, Object> messages = messagesOf(f);
        assertEquals("{at} 已拉黑 QQ {qq}{reason}", messages.get("qqban-ok"));
        assertEquals("{at} mode={mode} connected={connected} self_id={self_id} binds={binds} active_codes={codes}",
                messages.get("admin-status"));
        assertEquals("{at} 玩家 {target} 未绑定QQ", messages.get("admin-lookup-empty"));
        assertEquals("{at} {target} 无绑定", messages.get("admin-unbind-notfound"));
    }

    @Test
    void v9KeepsCustomizedSemanticRepairValueUntouched() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 8
                messages:
                  qqban-ok: "自定义拉黑提示 {result}"
                """);

        ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE);

        // 自定义优先；残留 {result} 留给启动期 MsgRenderer.validateAll 告警，升级器不强改
        assertEquals("自定义拉黑提示 {result}", messagesOf(f).get("qqban-ok"));
    }

    @Test
    void v6ToV9MultiHopKeepsCustomsAndWarnsOnce() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 6
                kick:
                  banned-message: "<red><b>该账号已被封禁</b></red>\\n<gray>原因：账号绑定的QQ已被服务器拉黑\\n如有异议请联系管理员申诉</gray>"
                messages:
                  bound: "老玩家的自定义文案"
                  qqban-ok: "{at} {result}"
                  admin-lookup: "{at} 自定义查询：\\n{result}"
                """);

        List<String> warnings = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));

        @SuppressWarnings("unchecked")
        Map<String, Object> back = new Yaml().load(Files.readString(f));
        assertEquals(9, ((Number) back.get("config-version")).intValue());
        // v7 迁移在多级跳里照常生效
        @SuppressWarnings("unchecked")
        Map<String, Object> kick = (Map<String, Object>) back.get("kick");
        assertTrue(((String) kick.get("banned-message")).contains("拉黑{reason}"), back.toString());
        // 用户自定义不丢
        @SuppressWarnings("unchecked")
        Map<String, Object> messages = (Map<String, Object>) back.get("messages");
        assertEquals("老玩家的自定义文案", messages.get("bound"));
        // 未自定义的语义修复键照常迁移
        assertEquals("{at} 已拉黑 QQ {qq}{reason}", messages.get("qqban-ok"));
        // 唯一定制过的退役键：移除且恰好一条点名告警（不重复）
        assertFalse(messages.containsKey("admin-lookup"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("messages.admin-lookup 已退役"), warnings.get(0));
    }

    @Test
    void v9UpgradeIsIdempotentAndNeverWarnsTwice() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, """
                config-version: 8
                messages:
                  self-unbind-list: "自定义列表 {result}"
                """);

        List<String> first = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));
        assertEquals(1, first.size(), first.toString());
        String afterFirst = Files.readString(f);

        var second = captureWarnings(() -> ConfigUpgrader.upgradeIfNeeded(f, V9_TEMPLATE));
        assertTrue(second.isEmpty(), "已是 v9 不应再告警: " + second);
        assertEquals(afterFirst, Files.readString(f), "重复升级不应再改写文件");
    }
}
