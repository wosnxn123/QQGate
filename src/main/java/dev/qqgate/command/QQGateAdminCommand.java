package dev.qqgate.command;
import dev.qqgate.QQGatePlugin;
import dev.qqgate.bind.BindStore;
import dev.qqgate.bind.BindService;
import dev.qqgate.onebot.OneBotEndpoint;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /qqgateadmin —— 管理员命令（权限 qqgate.admin，OP 默认）：
 *   help | status | codes | lookup &lt;玩家名|QQ&gt; | unbind &lt;玩家名|QQ&gt;
 *   | bind &lt;玩家名&gt; &lt;QQ&gt;（代绑，跳过验证码）| reload
 */
public final class QQGateAdminCommand implements CommandExecutor, TabCompleter {

    private final QQGatePlugin plugin;
    private final BindService binds;
    private final OneBotEndpoint endpoint;

    public QQGateAdminCommand(QQGatePlugin plugin, BindService binds, OneBotEndpoint endpoint) {
        this.plugin = plugin;
        this.binds = binds;
        this.endpoint = endpoint;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                OneBotEndpoint.Status st = endpoint.status();
                sender.sendMessage(String.format(
                        "§6[QQGate] §7mode=%s §fconnected=%s§7, self_id=%d, uptime=%ds, last_event=%ds ago, binds=%d, active_codes=%d",
                        st.mode(), st.connected(), st.selfId(), st.connectedSeconds(),
                        st.lastEventSecondsAgo(), binds.allBindings().size(), binds.activeCodeCount()));
                sender.sendMessage("§7[QQGate] 完整自检: /qqgateadmin diag");
            }
            case "codes" -> {
                List<BindService.PendingCode> codes = binds.activeCodes();
                if (codes.isEmpty()) {
                    sender.sendMessage("§7[QQGate] 无待验证码");
                    return true;
                }
                sender.sendMessage("§6[QQGate] §f待验证码 " + codes.size() + " 个：");
                for (BindService.PendingCode c : codes) {
                    sender.sendMessage(String.format("§7  %s §f%s §7(%ds 剩余)",
                            c.code(), c.name(), Math.max(0, (c.expiresAt() - System.currentTimeMillis()) / 1000)));
                }
            }
            case "lookup" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin lookup <玩家名|QQ号>");
                    return true;
                }
                String q = args[1];
                if (isNumeric(q)) {
                    long qq = Long.parseLong(q);
                    if (binds.store().isQqBanned(qq)) {
                        var meta = binds.store().bannedQqs().get(qq);
                        sender.sendMessage("§c[QQGate] ⚠ QQ " + qq + " 已被拉黑"
                                + (meta != null && !meta[1].isEmpty() ? "（原因: " + meta[1] + "）" : ""));
                    }
                    List<BindStore.Binding> list = binds.findByQq(qq);
                    if (list.isEmpty()) {
                        sender.sendMessage("§7[QQGate] QQ " + qq + " 未绑定任何账号");
                    } else {
                        sender.sendMessage("§6[QQGate] §fQQ " + qq + " 绑定 " + list.size() + " 个账号：");
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  " + b.name() + " §8· " + fmtTime(b.boundAt()));
                        }
                    }
                } else {
                    OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                    List<BindStore.Binding> list = binds.findByUuid(p.getUniqueId());
                    if (list.isEmpty()) {
                        sender.sendMessage("§7[QQGate] 玩家 " + q + " 未绑定QQ（尝试按名字全局检索）");
                        List<BindStore.Binding> byName = binds.allBindings().stream()
                                .filter(b -> b.name().equalsIgnoreCase(q)).toList();
                        if (byName.isEmpty()) {
                            sender.sendMessage("§7[QQGate] 按名字也未找到");
                        } else {
                            sender.sendMessage("§6[QQGate] §f按名字找到 " + byName.size() + " 条：");
                            for (BindStore.Binding b : byName) {
                                sender.sendMessage("§7  QQ " + b.qq() + " §8· " + fmtTime(b.boundAt()));
                            }
                        }
                    } else {
                        sender.sendMessage("§6[QQGate] §f玩家 " + q + " 绑定 " + list.size() + " 个QQ：");
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  QQ " + b.qq() + " §8· " + fmtTime(b.boundAt()));
                        }
                    }
                }
            }
            case "unbind" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin unbind <玩家名|QQ号> [QQ号]");
                    return true;
                }
                String q = args[1];
                // 双参：精确解绑 玩家名+QQ
                if (args.length >= 3 && isNumeric(args[2])) {
                    long qq = Long.parseLong(args[2]);
                    var list = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q) && b.qq() == qq).toList();
                    if (list.isEmpty()) {
                        sender.sendMessage("§7[QQGate] 未找到 " + q + " 与 QQ " + qq + " 的绑定");
                        return true;
                    }
                    for (BindStore.Binding b : list) {
                        binds.unbindExact(b.uuid(), b.qq());
                    }
                    long remain = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q)).count();
                    sender.sendMessage("§a[QQGate] 已解绑 " + q + " <-> QQ " + qq
                            + "（还剩 " + remain + " 条）");
                    return true;
                }
                if (isNumeric(q)) {
                    long qq = Long.parseLong(q);
                    List<BindStore.Binding> list = binds.findByQq(qq);
                    if (list.isEmpty()) {
                        sender.sendMessage("§7[QQGate] QQ " + q + " 无绑定");
                    } else if (list.size() == 1) {
                        binds.unbindExact(list.get(0).uuid(), qq);
                        sender.sendMessage("§a[QQGate] 已解绑 " + list.get(0).name() + " <-> QQ " + q);
                    } else {
                        sender.sendMessage("§6[QQGate] §fQQ " + q + " 名下有 " + list.size() + " 条绑定：");
                        int i = 1;
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  " + i++ + ". " + b.name()
                                    + " §8· " + fmtTime(b.boundAt()));
                        }
                        sender.sendMessage("§7精确解绑：/qqgateadmin unbind <玩家名> " + q
                                + " §8| 清空全部：/qqgateadmin unbindall " + q);
                    }
                } else {
                    var list = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q)).toList();
                    if (list.isEmpty()) {
                        // 回退 UUID 路径（处理改名孤儿）
                        OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                        int n = binds.unbindPlayer(p.getUniqueId());
                        sender.sendMessage(n > 0
                                ? "§a[QQGate] 已按UUID解绑玩家 " + q + " 的 " + n + " 条绑定"
                                : "§7[QQGate] 玩家 " + q + " 无绑定");
                    } else if (list.size() == 1) {
                        binds.unbindExact(list.get(0).uuid(), list.get(0).qq());
                        sender.sendMessage("§a[QQGate] 已解绑 " + q + " <-> QQ "
                                + list.get(0).qq());
                    } else {
                        sender.sendMessage("§6[QQGate] §f玩家 " + q + " 名下有 " + list.size() + " 条绑定：");
                        int i = 1;
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  " + i++ + ". QQ " + b.qq()
                                    + " §8· " + fmtTime(b.boundAt()));
                        }
                        sender.sendMessage("§7精确解绑：/qqgateadmin unbind " + q + " <QQ号>"
                                + " §8| 清空全部：/qqgateadmin unbindall " + q);
                    }
                }
            }
            case "unbindall" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin unbindall <玩家名|QQ号>");
                    return true;
                }
                String q = args[1];
                if (isNumeric(q)) {
                    int n = binds.unbindQq(Long.parseLong(q));
                    sender.sendMessage(n > 0
                            ? "§a[QQGate] 已清空 QQ " + q + " 名下 " + n + " 条绑定"
                            : "§7[QQGate] QQ " + q + " 无绑定");
                } else {
                    var list = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q)).toList();
                    int n = 0;
                    for (BindStore.Binding b : list) {
                        if (binds.unbindExact(b.uuid(), b.qq())) n++;
                    }
                    if (n == 0) {
                        OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                        n = binds.unbindPlayer(p.getUniqueId());
                    }
                    sender.sendMessage(n > 0
                            ? "§a[QQGate] 已清空玩家 " + q + " 名下 " + n + " 条绑定"
                            : "§7[QQGate] 玩家 " + q + " 无绑定");
                }
            }
            case "qqban" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin qqban <QQ号> [原因]");
                    return true;
                }
                long qq;
                try {
                    qq = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cQQ号格式错误: " + args[1]);
                    return true;
                }
                String reason = args.length >= 3
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
                var names = binds.qqban(qq, reason);
                sender.sendMessage("§a[QQGate] 已拉黑 QQ " + qq
                        + (reason.isEmpty() ? "" : "（原因: " + reason + "）"));
                sender.sendMessage(names.isEmpty()
                        ? "§7[QQGate] 该QQ名下无绑定（纯QQ拉黑，不涉名字封禁）"
                        : "§6[QQGate] 名下账号已封锁（绑定保留作案底）: §f" + String.join("、", names)
                        + "§7 —— 同名新连接将被拒绝；解拉黑后自动复原");
            }
            case "qqunban" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin qqunban <QQ号>");
                    return true;
                }
                long qq;
                try {
                    qq = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cQQ号格式错误: " + args[1]);
                    return true;
                }
                sender.sendMessage(binds.qqunban(qq)
                        ? "§a[QQGate] 已解除拉黑 QQ " + qq
                        : "§7[QQGate] QQ " + qq + " 不在黑名单");
            }
            case "qqbans" -> {
                var bans = binds.store().bannedQqs();
                if (bans.isEmpty()) {
                    sender.sendMessage("§7[QQGate] QQ 黑名单为空");
                    return true;
                }
                sender.sendMessage("§6[QQGate] §fQQ 黑名单（" + bans.size() + " 条）：");
                bans.forEach((qq, meta) -> {
                    var names = binds.store().namesOfQq(qq);
                    sender.sendMessage("§7  QQ " + qq + " §8· " + fmtTime(Long.parseLong(meta[0]))
                            + (meta[1].isEmpty() ? "" : " §7· " + meta[1])
                            + (names.isEmpty() ? "" : " §f· 名下: " + String.join("、", names)));
                });
            }
            case "bind" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /qqgateadmin bind <玩家名> <QQ号>");
                    return true;
                }
                String name = args[1];
                long qq;
                try {
                    qq = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cQQ号格式错误: " + args[2]);
                    return true;
                }
                OfflinePlayer p = plugin.getServer().getOfflinePlayer(name);
                UUID uuid = p.getUniqueId();
                // 复用裁决链：管理员代绑视为信任提交
                var r = binds.adminBind(uuid, name, qq, System.currentTimeMillis());
                sender.sendMessage(switch (r.outcome()) {
                    case SUCCESS -> "§a[QQGate] 已绑定 " + name + " <-> QQ " + qq;
                    case SUCCESS_REPLACED -> "§a[QQGate] 已绑定（挤下 " + r.evicted().name() + "）"
                            + name + " <-> QQ " + qq;
                    case QQ_FULL -> "§c[QQGate] QQ " + qq + " 已绑定满 "
                            + binds.settings().maxPerQq + " 个账号（limit-policy=reject）";
                    case QQ_BANNED -> "§c[QQGate] ⚠ QQ " + qq + " 已被拉黑，请先 /qqgateadmin qqunban " + qq;
                    case PLAYER_FULL -> "§c[QQGate] 玩家 " + name + " 已绑定满 "
                            + binds.settings().maxPerPlayer + " 个QQ（limit-policy=reject）";
                    default -> "§c[QQGate] 绑定失败: " + r.outcome();
                });
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§a[QQGate] 配置已重载（连接参数 mode/端口需重启生效）");
            }
            case "diag" -> {
                var cfg = plugin.getConfig();
                sender.sendMessage("§6[QQGate] §f── 运行时配置自检 ──");
                sender.sendMessage("§7配置版本: §f" + cfg.getInt("config-version", 0)
                        + " §8| op-skip=" + plugin.joinListenerSkip()
                        + " §8| self-unbind=" + binds.settings().selfUnbind
                        + " §8| private-bind=" + cfg.getBoolean("private.allow-bind", false));
                sender.sendMessage("§7限额: §f" + binds.settings().maxPerQq + "qq/"
                        + binds.settings().maxPerPlayer + "player/"
                        + cfg.getString("bind.limit-policy", "reject")
                        + " §8| 冷却=" + binds.settings().cooldownSeconds + "s"
                        + " §8| 码长=" + binds.settings().codeLength
                        + " §8| 有效期=" + Math.max(1, cfg.getInt("bind.expire-minutes", 5)) + "m");
                var groups = cfg.getStringList("groups.allowed");
                sender.sendMessage("§7群: §f白名单" + groups.size() + "个"
                        + (groups.isEmpty() ? "§c（空！）" : "")
                        + " §8| allow-all=" + cfg.getBoolean("groups.allow-all", false)
                        + " §8| 推荐群=" + (cfg.getString("groups.recommended", "").isEmpty() ? "无" : "已设"));
                var st = endpoint.status();
                sender.sendMessage("§7连接: §f" + st.mode() + " " + cfg.getString("onebot.listen-host", "0.0.0.0")
                        + ":" + cfg.getInt("onebot.listen-port", 6700)
                        + " §8| connected=" + st.connected()
                        + " §8| self_id=" + st.selfId()
                        + " §8| token=" + (cfg.getString("onebot.access-token", "").isEmpty() ? "§c未设置" : "已设置"));
                sender.sendMessage("§7数据: §fbinds=" + binds.allBindings().size()
                        + " §8| qqbans=" + binds.store().bannedQqs().size()
                        + " §8| active_codes=" + binds.activeCodeCount()
                        + " §8| admins=" + cfg.getStringList("admins.qq").size());
                // 文件可写检查
                var dataDir = plugin.getDataFolder().toPath();
                boolean w1 = java.nio.file.Files.isWritable(dataDir.resolve("bindings.json"));
                sender.sendMessage("§7文件: §fbindings.json " + (w1 ? "§a可写✓" : "§c不可写✗")
                        + " §8| banned_qqs.json " + (java.nio.file.Files.isWritable(dataDir.resolve("banned_qqs.json")) ? "§a可写✓" : "§c不可写✗"));
                if (!st.connected()) {
                    sender.sendMessage("§c[QQGate] ⚠ 机器人未连接——绑定指令无响应，检查 NapCat 反向WS地址/端口/网络");
                }
                if (groups.isEmpty() && !cfg.getBoolean("groups.allow-all", false)) {
                    sender.sendMessage("§c[QQGate] ⚠ 群白名单为空且未开 allow-all——所有群指令静默忽略");
                }
            }
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        return s.chars().allMatch(Character::isDigit) && !s.isEmpty();
    }

    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String fmtTime(long epochMilli) {
        return FMT.format(java.time.Instant.ofEpochMilli(epochMilli)
                .atZone(java.time.ZoneId.systemDefault()));
    }


    private void sendHelp(CommandSender sender) {
        sender.sendMessage("""
                §6[QQGate] 管理员命令：
                §f  /qqgateadmin status              §7连接状态与统计
                §f  /qqgateadmin codes               §7当前待验证码
                §f  /qqgateadmin diag                §7运行时配置自检（含健康警告）
                §f  /qqgateadmin unbind <名|QQ> [QQ] §7解绑（多条列出，双参精确）
                §f  /qqgateadmin unbindall <名|QQ>   §7清空目标全部绑定
                §f  /qqgateadmin bind <玩家> <QQ>    §7代绑（跳过验证码）
                §f  /qqgateadmin qqban <QQ> [原因]   §7拉黑QQ（名下+同名账号全封锁）
                §f  /qqgateadmin qqunban <QQ>        §7解除拉黑（自动复原）
                §f  /qqgateadmin qqbans              §7QQ黑名单列表（含名下账号名）
                §f  /qqgateadmin reload              §7重载配置""");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "diag", "codes", "lookup", "unbind", "unbindall", "bind", "qqban", "qqunban", "qqbans", "reload", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("unbind")
                || args[0].equalsIgnoreCase("unbindall"))) {
            return null; // 玩家名自动补全
        }
        return List.of();
    }
}
