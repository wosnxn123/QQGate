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
                                sender.sendMessage("§7  QQ " + maskQq(b.qq()) + " §8· " + fmtTime(b.boundAt()));
                            }
                        }
                    } else {
                        sender.sendMessage("§6[QQGate] §f玩家 " + q + " 绑定 " + list.size() + " 个QQ：");
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  QQ " + maskQq(b.qq()) + " §8· " + fmtTime(b.boundAt()));
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
                    case PLAYER_FULL -> "§c[QQGate] 玩家 " + name + " 已绑定满 "
                            + binds.settings().maxPerPlayer + " 个QQ（limit-policy=reject）";
                    default -> "§c[QQGate] 绑定失败: " + r.outcome();
                });
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§a[QQGate] 配置已重载（连接参数 mode/端口需重启生效）");
            }
            default -> sendHelp(sender);
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

    private static String maskQq(long qq) {
        String s = String.valueOf(qq);
        return s.length() <= 4 ? s : s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("""
                §6[QQGate] 管理员命令：
                §f  /qqgateadmin status              §7连接状态与统计
                §f  /qqgateadmin codes               §7当前待验证码
                §f  /qqgateadmin lookup <名|QQ>      §7查询绑定
                §f  /qqgateadmin unbind <名|QQ> [QQ] §7解绑（多条列出，双参精确）
                §f  /qqgateadmin unbindall <名|QQ>   §7清空目标全部绑定
                §f  /qqgateadmin bind <玩家> <QQ>    §7代绑（跳过验证码）
                §f  /qqgateadmin reload              §7重载配置""");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "codes", "lookup", "unbind", "unbindall", "bind", "reload", "help").stream()
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
