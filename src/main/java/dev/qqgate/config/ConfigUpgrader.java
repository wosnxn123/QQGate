package dev.qqgate.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 配置自动升级：旧 config.yml 与内置模板按顶层段合并，补齐缺失键。
 *
 * 规则：
 * - 值只增不改：旧文件已有的键值与顶层键序原样保留；仅两类有序迁移例外——
 *   语义变更的默认文案（v7/v9）在未自定义旧值时迁移，无代码路径的退役键（v9）主动移除并按名告警自定义者
 * - 缺段整段追加；段内缺键在段尾追加
 * - 注释一律丢失：写回是 snakeyaml dump 全文重写，用户手写注释与模板行注释
 *   都还不回来（不只是新增段受影响）；文件头会如实写明并指向备份文件
 * - 写回前备份原文件为 config.yml.bak-<时间戳>
 * - config-version 已是最新 → 完全不动文件（注释也就不会无谓丢失）
 *
 * 纯 Java + snakeyaml（服务端自带），可单测。
 */
public final class ConfigUpgrader {

    public record Result(boolean upgraded, int fromVersion, int toVersion, int addedKeys, String backup) {
        public static Result noop(int version) {
            return new Result(false, version, version, 0, null);
        }
    }

    private ConfigUpgrader() {
    }

    /** 与插件日志同名（plugin.yml name: QQGate）：升级告警直接打进服务器控制台。 */
    private static final Logger LOG = Logger.getLogger("QQGate");

    /**
     * 执行升级检查。
     *
     * @param configFile 用户配置文件（可能不存在）
     * @param templateText 内置模板全文（资源 config.yml）
     */
    public static Result upgradeIfNeeded(Path configFile, String templateText) throws IOException {
        Map<String, Object> template = loadYaml(templateText);
        int targetVersion = versionOf(template);
        if (!Files.exists(configFile)) {
            return Result.noop(targetVersion); // 全新安装：saveDefaultConfig 会写完整模板
        }
        String currentText = Files.readString(configFile, StandardCharsets.UTF_8);
        Map<String, Object> current = loadYaml(currentText);
        if (current.isEmpty()) {
            return Result.noop(targetVersion); // 解析失败：不碰，留给 Bukkit 报错
        }
        int currentVersion = versionOf(current);
        if (currentVersion >= targetVersion) {
            return Result.noop(currentVersion);
        }

        // 备份
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = configFile.resolveSibling(configFile.getFileName() + ".bak-" + stamp);
        Files.copy(configFile, backup);

        // 合并：模板为骨架，用户值优先
        MergeOutcome merged = deepMerge(current, template);
        // 值迁移：语义变更的默认文案仅在值仍等于旧默认（未自定义）时换新默认；用户改过的值不动
        migrateLegacyDefaults(merged.root());
        // 退役键清理（v9）：无代码路径的旧键必须主动移除（deepMerge 只增不删），自定义过的点名告警
        removeRetiredKeys(merged.root());
        // 版本号对齐到模板
        merged.root().put("config-version", targetVersion);

        String mergedText = renderWithHeader(merged.root(), targetVersion, merged.added(),
                backup.getFileName().toString());
        Files.writeString(configFile, mergedText, StandardCharsets.UTF_8);
        return new Result(true, currentVersion, targetVersion, merged.added(),
                backup.getFileName().toString());
    }

    private record MergeOutcome(Map<String, Object> root, int added) {
    }

    /** 文案迁移项：路径 + 旧默认值 + 新默认值。 */
    private record TextMigration(String path, String oldDefault, String newDefault) { }

    /** v7：封禁文案加 {reason} 占位。仅当值仍等于旧默认（未自定义）才替换。 */
    private static final List<TextMigration> V7_TEXT_MIGRATIONS = List.of(
            new TextMigration("kick.banned-message",
                    "<red><b>该账号已被封禁</b></red>\n<gray>原因：账号绑定的QQ已被服务器拉黑\n如有异议请联系管理员申诉</gray>",
                    "<red><b>该账号已被封禁</b></red>\n<gray>原因：账号绑定的QQ已被服务器拉黑{reason}\n如有异议请联系管理员申诉</gray>"),
            new TextMigration("kick.name-banned-message",
                    "<red><b>该名称已被封禁</b></red>\n<gray>原因：此名称的历史账号曾绑定被拉黑的QQ\n如你是新玩家且首次使用此名称，请联系管理员处理</gray>",
                    "<red><b>该名称已被封禁</b></red>\n<gray>原因：此名称的历史账号曾绑定被拉黑的QQ{reason}\n如你是新玩家且首次使用此名称，请联系管理员处理</gray>"),
            new TextMigration("messages.qq-banned",
                    "{at} 该QQ已被服务器拉黑，无法绑定；如有异议请联系管理员",
                    "{at} 该QQ已被服务器拉黑{reason}，无法绑定；如有异议请联系管理员"));

    /**
     * v9 语义修复键：路径保留但默认文案改写（{result} 拆解、死占位符清除）。
     * 未自定义的旧默认照迁移；自定义值原样保留——残留 {result} 会在启动期被
     * MsgRenderer.validateAll 当未知 token 告警，由管理员按告警自行修正。
     */
    private static final List<TextMigration> V9_TEXT_MIGRATIONS = List.of(
            new TextMigration("messages.qqban-ok",
                    "{at} {result}",
                    "{at} 已拉黑 QQ {qq}{reason}"),
            new TextMigration("messages.admin-status",
                    "{at} {result}",
                    "{at} mode={mode} connected={connected} self_id={self_id} binds={binds} active_codes={codes}"),
            new TextMigration("messages.admin-lookup-empty",
                    "{at} {target} 未找到绑定",
                    "{at} 玩家 {target} 未绑定QQ"),
            new TextMigration("messages.admin-unbind-notfound",
                    "{at} {target} 无绑定（或未找到该组合）",
                    "{at} {target} 无绑定"));

    private static void migrateLegacyDefaults(Map<String, Object> root) {
        applyTextMigrations(root, V7_TEXT_MIGRATIONS);
        applyTextMigrations(root, V9_TEXT_MIGRATIONS);
    }

    /** 逐项比对替换：值与旧默认完全相等 → 换新默认；否则视为用户自定义，不动。 */
    private static void applyTextMigrations(Map<String, Object> root, List<TextMigration> migrations) {
        for (TextMigration m : migrations) {
            String[] segs = m.path().split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < segs.length - 1 && node != null; i++) {
                Object next = node.get(segs[i]);
                node = next instanceof Map ? castMap(next) : null;
            }
            if (node != null && m.oldDefault().equals(node.get(segs[segs.length - 1]))) {
                node.put(segs[segs.length - 1], m.newDefault());
            }
        }
    }

    /** v9 退役键：配置路径 + 旧默认值（判断是否自定义过）+ 拆成的新键（告警点名）。 */
    private record RetiredKey(String path, String oldDefault, List<String> newKeys) { }

    /**
     * v9 退役键表：塞 {result} 的旧单键已拆成 头/条目/提示 多键，代码不再读取旧路径。
     * deepMerge 只增不删，升级时必须主动移除，否则用户会以为自己的自定义排版还生效。
     */
    private static final List<RetiredKey> V9_RETIRED_KEYS = List.of(
            new RetiredKey("messages.self-unbind-list", "{at} {result}", List.of(
                    "messages.self-unbind-list-header",
                    "messages.self-unbind-list-item",
                    "messages.self-unbind-list-hint")),
            new RetiredKey("messages.qqbans-list", "{at} {result}", List.of(
                    "messages.qqbans-list-header",
                    "messages.qqbans-list-item")),
            new RetiredKey("messages.admin-lookup", "{at} {result}", List.of(
                    "messages.admin-lookup-banned-note",
                    "messages.admin-lookup-qq-empty",
                    "messages.admin-lookup-qq-header",
                    "messages.admin-lookup-qq-item",
                    "messages.admin-lookup-player-header",
                    "messages.admin-lookup-player-item")),
            new RetiredKey("messages.admin-unbind-ambiguous", "{at} {result}", List.of(
                    "messages.admin-unbind-ambiguous-header",
                    "messages.admin-unbind-ambiguous-item",
                    "messages.admin-unbind-ambiguous-hint")));

    /**
     * 移除退役键；值与旧默认不同（即用户自定义过）的键各打一条告警，点名拆成的新键，
     * 提示按新键重新自定义。值仍为旧默认的键静默移除，避免刷屏。
     */
    private static void removeRetiredKeys(Map<String, Object> root) {
        for (RetiredKey r : V9_RETIRED_KEYS) {
            String[] segs = r.path().split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < segs.length - 1 && node != null; i++) {
                Object next = node.get(segs[i]);
                node = next instanceof Map ? castMap(next) : null;
            }
            if (node == null || !node.containsKey(segs[segs.length - 1])) continue;
            Object old = node.remove(segs[segs.length - 1]);
            if (!r.oldDefault().equals(old)) {
                LOG.warning("配置键 " + r.path() + " 已退役并被移除（v9 拆键重构后代码不再读取）；"
                        + "你自定义的文案已失效，新结构拆为：" + String.join("、", r.newKeys())
                        + "，请按新键重新自定义");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * 以 template 为参照补齐 current 缺失的键。
     * 用户已有键值原样保留；缺失的整段/键从模板拷贝（保持模板顺序：新段在尾，段内新键在段尾）。
     */
    private static MergeOutcome deepMerge(Map<String, Object> current, Map<String, Object> template) {
        int added = 0;
        for (Map.Entry<String, Object> e : template.entrySet()) {
            String key = e.getKey();
            Object tVal = e.getValue();
            if ("config-version".equals(key)) continue; // 外部处理
            Object cVal = current.get(key);
            if (cVal == null) {
                current.put(key, tVal);
                added += countLeaves(tVal);
            } else if (tVal instanceof Map<?, ?> tm && cVal instanceof Map<?, ?> cm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sub = (Map<String, Object>) cm;
                @SuppressWarnings("unchecked")
                Map<String, Object> subT = (Map<String, Object>) tm;
                added += mergeSection(sub, subT);
            }
            // 同名标量/列表：用户值优先，不动
        }
        return new MergeOutcome(current, added);
    }

    private static int mergeSection(Map<String, Object> current, Map<String, Object> template) {
        int added = 0;
        for (Map.Entry<String, Object> e : template.entrySet()) {
            Object tVal = e.getValue();
            Object cVal = current.get(e.getKey());
            if (cVal == null) {
                current.put(e.getKey(), tVal);
                added += countLeaves(tVal);
            } else if (tVal instanceof Map<?, ?> tm && cVal instanceof Map<?, ?> cm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sub = (Map<String, Object>) cm;
                @SuppressWarnings("unchecked")
                Map<String, Object> subT = (Map<String, Object>) tm;
                added += mergeSection(sub, subT);
            }
        }
        return added;
    }

    private static int countLeaves(Object v) {
        if (v instanceof Map<?, ?> m) {
            int n = 0;
            for (Object x : m.values()) n += countLeaves(x);
            return n;
        }
        return 1;
    }

    private static int versionOf(Map<String, Object> yaml) {
        Object v = yaml.get("config-version");
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 1; // 无版本标记 = 最初版本
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = new Yaml().load(text);
            return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 渲染：snakeyaml dump 全文重写，顶层键序保留（用户原序 + 新段尾随）。
     * 代价是原文件所有注释（含用户手写的）都拿不回来，只有键值与顺序保得住——
     * 文件头必须如实告知并给出备份文件名，别让用户以为只丢了新增段的注释。
     */
    private static String renderWithHeader(Map<String, Object> root, int targetVersion,
                                           int added, String backupName) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setProcessComments(false);
        String body = new Yaml(opts).dump(root);
        String header = "# QQGate 配置 | 已自动升级到 v" + targetVersion
                + "（新增 " + added + " 项，取模板默认值）\n"
                + "# 注意：升级为整文件重写，本文件原有的注释（含你手写的）已全部丢失；\n"
                + "#       自定义值与键顺序均已保留，原文件完整备份在同目录 " + backupName + "\n"
                + "# 各项说明见插件内模板或仓库 src/main/resources/config.yml\n"
                + "# 改完用 /qqgateadmin reload 生效（连接段除外）\n\n";
        return header + body;
    }

    /** 供主类生成启动摘要（不参与升级逻辑）。 */
    public static List<String> summaryOf(org.bukkit.configuration.file.FileConfiguration cfg) {
        return List.of(
                "mode=" + cfg.getString("onebot.mode", "reverse-ws"),
                "listen=" + cfg.getString("onebot.listen-host", "0.0.0.0") + ":" + cfg.getInt("onebot.listen-port", 6700),
                "token=" + (cfg.getString("onebot.access-token", "").isEmpty() ? "未设置(不鉴权)" : "已设置"),
                "groups=" + (cfg.getBoolean("groups.allow-all", false)
                        ? "全部允许" : cfg.getStringList("groups.allowed").size() + " 个白名单群"),
                "private-bind=" + cfg.getBoolean("private.allow-bind", false),
                "admins=" + cfg.getStringList("admins.qq").size() + " 个",
                "self-unbind=" + cfg.getBoolean("bind.self-unbind", false),
                "limit=" + cfg.getInt("bind.max-per-qq", 1) + "qq/" + cfg.getInt("bind.max-per-player", 1)
                        + "player/" + cfg.getString("bind.limit-policy", "reject"));
    }
}
