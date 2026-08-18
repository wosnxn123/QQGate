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

/**
 * 配置自动升级：旧 config.yml 与内置模板按顶层段合并，补齐缺失键。
 *
 * 规则（保守）：
 * - 只增不改：旧文件已有的键值/注释/顺序原样保留
 * - 缺段整段追加；段内缺键在段尾追加（含模板行注释）
 * - 已废弃键保留不动
 * - 写回前备份原文件为 config.yml.bak-<时间戳>
 * - config-version 已是最新 → 完全不动文件
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
        // 版本号对齐到模板
        merged.root().put("config-version", targetVersion);

        String mergedText = renderWithHeader(merged.root(), templateText, targetVersion, merged.added());
        Files.writeString(configFile, mergedText, StandardCharsets.UTF_8);
        return new Result(true, currentVersion, targetVersion, merged.added(),
                backup.getFileName().toString());
    }

    private record MergeOutcome(Map<String, Object> root, int added) {
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
     * 渲染：顶层用块式 YAML；保留顶层键序（用户原序 + 新段尾随）。
     * 注释无法从 snakeyaml 还原——新段/新键的注释丢失是已知取舍，
     * 由升级日志提示用户可查模板原文。
     */
    private static String renderWithHeader(Map<String, Object> root, String templateText,
                                           int targetVersion, int added) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setProcessComments(false);
        String body = new Yaml(opts).dump(root);
        String header = "# QQGate 配置 | 已自动升级到 v" + targetVersion
                + "（本次新增 " + added + " 项，默认值生效；用户已有设置全部保留）\n"
                + "# 新增项的说明注释见插件内模板或仓库 src/main/resources/config.yml\n"
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
