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
                            sender.sendMessage("§7  " + b.name() + " §8(" + b.uuid() + ")");
                        }
                    }
                } else {
                    OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                    List<BindStore.Binding> list = binds.findByUuid(p.getUniqueId());
                    if (list.isEmpty()) {
                        sender.sendMessage("§7[QQGate] 玩家 " + q + " 未绑定QQ");
                    } else {
                        sender.sendMessage("§6[QQGate] §f玩家 " + q + " 绑定 " + list.size() + " 个QQ：");
                        for (BindStore.Binding b : list) {
                            sender.sendMessage("§7  QQ " + b.qq() + " §8(bound " + b.boundAt() + ")");
                        }
                    }
                }
            }
            case "unbind" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /qqgateadmin unbind <玩家名|QQ号>");
                    return true;
                }
                String q = args[1];
                if (isNumeric(q)) {
                    int n = binds.unbindQq(Long.parseLong(q));
                    sender.sendMessage(n > 0
                            ? "§a[QQGate] 已解绑 QQ " + q + " 的 " + n + " 条绑定"
                            : "§7[QQGate] QQ " + q + " 无绑定");
                } else {
                    OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                    int n = binds.unbindPlayer(p.getUniqueId());
                    sender.sendMessage(n > 0
                            ? "§a[QQGate] 已解绑玩家 " + q + " 的 " + n + " 条绑定"
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("""
                §6[QQGate] 管理员命令：
                §f  /qqgateadmin status              §7连接状态与统计
                §f  /qqgateadmin codes               §7当前待验证码
                §f  /qqgateadmin lookup <名|QQ>      §7查询绑定
                §f  /qqgateadmin unbind <名|QQ>      §7解绑
                §f  /qqgateadmin bind <玩家> <QQ>    §7代绑（跳过验证码）
                §f  /qqgateadmin reload              §7重载配置""");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "codes", "lookup", "unbind", "bind", "reload", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("unbind"))) {
            return null; // 玩家名自动补全
        }
        return List.of();
    }
}
