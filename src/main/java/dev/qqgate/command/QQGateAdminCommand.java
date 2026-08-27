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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * /qqgateadmin —— 管理员命令（权限 qqgate.admin，OP 默认）。
 * <p>子指令表见 {@link AdminSub}：派发、帮助、Tab 补全同源。未知子指令提示后显示帮助，
 * 漏接线的子指令落到 {@code default} 也显示帮助——任何输入都必有回执，绝不静默。
 */
public final class QQGateAdminCommand implements CommandExecutor, TabCompleter {

    /** 帮助与用法提示里显示的命令本体。 */
    private static final String CMD = "qqgateadmin";

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
        Optional<AdminSub> parsed = AdminSub.of(args.length == 0 ? "" : args[0]);
        if (parsed.isEmpty()) {
            sender.sendMessage(Msg.err("未知子指令 §f" + args[0] + "§c，可用指令："));
            sendHelp(sender);
            return true;
        }
        switch (parsed.get()) {
            case HELP -> sendHelp(sender);
            case STATUS -> {
                OneBotEndpoint.Status st = endpoint.status();
                sender.sendMessage(Msg.header("连接状态"));
                sender.sendMessage(Msg.field("模式", st.mode()));
                sender.sendMessage(Msg.field("连接", st.connected() ? "§a已连接 ✓" : "§c未连接 ✗"));
                sender.sendMessage(Msg.field("机器人QQ", st.selfId() > 0 ? String.valueOf(st.selfId()) : "§7未上报"));
                sender.sendMessage(Msg.field("已连接", fmtDur(st.connectedSeconds())));
                sender.sendMessage(Msg.field("最近事件", st.lastEventSecondsAgo() < 0
                        ? "§7从未" : fmtDur(st.lastEventSecondsAgo()) + " 前"));
                sender.sendMessage(Msg.field("绑定", binds.allBindings().size() + " 条"
                        + " §8| 黑名单 " + binds.store().bannedQqs().size() + " 条"));
                sender.sendMessage(Msg.field("待验证码", binds.activeCodeCount() + " 个"));
                sender.sendMessage(Msg.footer("完整自检 §f/" + CMD + " diag"));
            }
            case CODES -> {
                List<BindService.PendingCode> codes = binds.activeCodes();
                if (codes.isEmpty()) {
                    sender.sendMessage(Msg.info("当前无待验证的验证码"));
                    return true;
                }
                sender.sendMessage(Msg.title("待验证码 §e" + codes.size() + " §f个："));
                for (BindService.PendingCode c : codes) {
                    long left = Math.max(0, (c.expiresAt() - System.currentTimeMillis()) / 1000);
                    sender.sendMessage(Msg.item("§e" + c.code() + " §f" + c.name(), "剩余 " + fmtDur(left)));
                }
                sender.sendMessage(Msg.footer("玩家在群里发 §f#验证 <码> §7完成绑定"));
            }
            case LOOKUP -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " lookup", AdminSub.LOOKUP.args()));
                    return true;
                }
                String q = args[1];
                if (isNumeric(q)) {
                    long qq = Long.parseLong(q);
                    if (binds.store().isQqBanned(qq)) {
                        var meta = binds.store().bannedQqs().get(qq);
                        sender.sendMessage(Msg.warn("QQ " + qq + " 已被拉黑"
                                + (meta != null && !meta[1].isEmpty() ? " §7原因: §f" + meta[1] : "")));
                    }
                    List<BindStore.Binding> list = binds.findByQq(qq);
                    if (list.isEmpty()) {
                        sender.sendMessage(Msg.info("QQ " + qq + " 未绑定任何账号"));
                    } else {
                        sender.sendMessage(Msg.title("QQ §e" + qq + " §f绑定 " + list.size() + " 个账号："));
                        for (BindStore.Binding b : list) {
                            sender.sendMessage(Msg.item(b.name(), fmtTime(b.boundAt())));
                        }
                    }
                } else {
                    OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                    List<BindStore.Binding> list = binds.findByUuid(p.getUniqueId());
                    if (list.isEmpty()) {
                        List<BindStore.Binding> byName = binds.allBindings().stream()
                                .filter(b -> b.name().equalsIgnoreCase(q)).toList();
                        if (byName.isEmpty()) {
                            sender.sendMessage(Msg.info("玩家 " + q + " 未绑定QQ（UUID 与名字均未命中）"));
                        } else {
                            sender.sendMessage(Msg.title("玩家 §e" + q + " §f按名字找到 " + byName.size() + " 条："));
                            for (BindStore.Binding b : byName) {
                                sender.sendMessage(Msg.item("QQ " + b.qq(), fmtTime(b.boundAt())));
                            }
                            sender.sendMessage(Msg.footer("UUID 未命中，可能是改名后的历史绑定"));
                        }
                    } else {
                        sender.sendMessage(Msg.title("玩家 §e" + q + " §f绑定 " + list.size() + " 个QQ："));
                        for (BindStore.Binding b : list) {
                            sender.sendMessage(Msg.item("QQ " + b.qq(), fmtTime(b.boundAt())));
                        }
                    }
                }
            }
            case UNBIND -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " unbind", AdminSub.UNBIND.args()));
                    return true;
                }
                String q = args[1];
                // 双参：玩家名 + QQ 精确解绑
                if (args.length >= 3) {
                    if (!isNumeric(args[2])) {
                        sender.sendMessage(Msg.err("第二个参数必须是QQ号：§f" + args[2]));
                        sender.sendMessage(Msg.usage(CMD + " unbind", AdminSub.UNBIND.args()));
                        return true;
                    }
                    long qq = Long.parseLong(args[2]);
                    var list = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q) && b.qq() == qq).toList();
                    if (list.isEmpty()) {
                        sender.sendMessage(Msg.info("未找到 " + q + " 与 QQ " + qq + " 的绑定"));
                        return true;
                    }
                    for (BindStore.Binding b : list) {
                        binds.unbindExact(b.uuid(), b.qq());
                    }
                    long remain = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q)).count();
                    sender.sendMessage(Msg.ok("已解绑 §f" + q + " §a<-> QQ §f" + qq
                            + " §7（该玩家还剩 " + remain + " 条）"));
                    return true;
                }
                if (isNumeric(q)) {
                    long qq = Long.parseLong(q);
                    List<BindStore.Binding> list = binds.findByQq(qq);
                    if (list.isEmpty()) {
                        sender.sendMessage(Msg.info("QQ " + q + " 无绑定"));
                    } else if (list.size() == 1) {
                        binds.unbindExact(list.get(0).uuid(), qq);
                        sender.sendMessage(Msg.ok("已解绑 §f" + list.get(0).name() + " §a<-> QQ §f" + q));
                    } else {
                        sender.sendMessage(Msg.title("QQ §e" + q + " §f名下有 " + list.size() + " 条绑定："));
                        int i = 1;
                        for (BindStore.Binding b : list) {
                            sender.sendMessage(Msg.item(i++, b.name(), fmtTime(b.boundAt())));
                        }
                        sender.sendMessage(Msg.hint("精确解绑 §f/" + CMD + " unbind <玩家名> " + q));
                        sender.sendMessage(Msg.hint("全部清空 §f/" + CMD + " unbindall " + q));
                    }
                } else {
                    var list = binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(q)).toList();
                    if (list.isEmpty()) {
                        // 回退 UUID 路径：处理改名产生的孤儿绑定
                        OfflinePlayer p = plugin.getServer().getOfflinePlayer(q);
                        int n = binds.unbindPlayer(p.getUniqueId());
                        sender.sendMessage(n > 0
                                ? Msg.ok("已按UUID解绑玩家 §f" + q + " §a的 " + n + " 条绑定")
                                : Msg.info("玩家 " + q + " 无绑定"));
                    } else if (list.size() == 1) {
                        binds.unbindExact(list.get(0).uuid(), list.get(0).qq());
                        sender.sendMessage(Msg.ok("已解绑 §f" + q + " §a<-> QQ §f" + list.get(0).qq()));
                    } else {
                        sender.sendMessage(Msg.title("玩家 §e" + q + " §f名下有 " + list.size() + " 条绑定："));
                        int i = 1;
                        for (BindStore.Binding b : list) {
                            sender.sendMessage(Msg.item(i++, "QQ " + b.qq(), fmtTime(b.boundAt())));
                        }
                        sender.sendMessage(Msg.hint("精确解绑 §f/" + CMD + " unbind " + q + " <QQ号>"));
                        sender.sendMessage(Msg.hint("全部清空 §f/" + CMD + " unbindall " + q));
                    }
                }
            }
            case UNBINDALL -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " unbindall", AdminSub.UNBINDALL.args()));
                    return true;
                }
                String q = args[1];
                if (isNumeric(q)) {
                    int n = binds.unbindQq(Long.parseLong(q));
                    sender.sendMessage(n > 0
                            ? Msg.ok("已清空 QQ §f" + q + " §a名下 " + n + " 条绑定")
                            : Msg.info("QQ " + q + " 无绑定"));
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
                            ? Msg.ok("已清空玩家 §f" + q + " §a名下 " + n + " 条绑定")
                            : Msg.info("玩家 " + q + " 无绑定"));
                }
            }
            case BIND -> {
                if (args.length < 3) {
                    sender.sendMessage(Msg.usage(CMD + " bind", AdminSub.BIND.args()));
                    return true;
                }
                String name = args[1];
                long qq;
                try {
                    qq = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[2]));
                    return true;
                }
                OfflinePlayer p = plugin.getServer().getOfflinePlayer(name);
                UUID uuid = p.getUniqueId();
                // 复用裁决链：管理员代绑视为信任提交，但限额/黑名单仍然生效
                var r = binds.adminBind(uuid, name, qq, System.currentTimeMillis());
                sender.sendMessage(switch (r.outcome()) {
                    case SUCCESS -> Msg.ok("已绑定 §f" + name + " §a<-> QQ §f" + qq);
                    case SUCCESS_REPLACED -> Msg.ok("已绑定 §f" + name + " §a<-> QQ §f" + qq
                            + " §7（挤下最旧的 " + r.evicted().name() + "）");
                    case QQ_FULL -> Msg.err("QQ " + qq + " 已绑满 "
                            + binds.settings().maxPerQq + " 个账号（limit-policy=reject）");
                    case QQ_BANNED -> Msg.err("QQ " + qq + " 在黑名单中，先执行 §f/" + CMD + " qqunban " + qq);
                    case PLAYER_FULL -> Msg.err("玩家 " + name + " 已绑满 "
                            + binds.settings().maxPerPlayer + " 个QQ（limit-policy=reject）");
                    default -> Msg.err("绑定失败：" + r.outcome());
                });
            }
            case QQBAN -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " qqban", AdminSub.QQBAN.args()));
                    return true;
                }
                long qq;
                try {
                    qq = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[1]));
                    return true;
                }
                String reason = args.length >= 3
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
                var names = binds.qqban(qq, reason);
                sender.sendMessage(Msg.ok("已拉黑 QQ §f" + qq
                        + (reason.isEmpty() ? "" : " §7原因: §f" + reason)));
                if (names.isEmpty()) {
                    sender.sendMessage(Msg.footer("该QQ名下无绑定——纯QQ拉黑，不涉及名字封禁"));
                } else {
                    sender.sendMessage(Msg.item("已封锁账号", String.join("、", names)));
                    sender.sendMessage(Msg.footer("绑定保留作案底；同名新连接一律拒绝，解拉黑后自动复原"));
                }
            }
            case QQUNBAN -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " qqunban", AdminSub.QQUNBAN.args()));
                    return true;
                }
                long qq;
                try {
                    qq = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[1]));
                    return true;
                }
                sender.sendMessage(binds.qqunban(qq)
                        ? Msg.ok("已解除拉黑 QQ §f" + qq + " §7名下账号自动复原")
                        : Msg.info("QQ " + qq + " 不在黑名单"));
            }
            case QQBANS -> {
                var bans = binds.store().bannedQqs();
                if (bans.isEmpty()) {
                    sender.sendMessage(Msg.info("QQ 黑名单为空"));
                    return true;
                }
                sender.sendMessage(Msg.title("QQ 黑名单 §e" + bans.size() + " §f条："));
                bans.forEach((qq, meta) -> {
                    var names = binds.store().namesOfQq(qq);
                    StringBuilder note = new StringBuilder(fmtTimeSafe(meta[0]));
                    if (!meta[1].isEmpty()) note.append(" §8· §7").append(meta[1]);
                    if (!names.isEmpty()) note.append(" §8· §f名下: ").append(String.join("、", names));
                    sender.sendMessage(Msg.item("QQ " + qq, note.toString()));
                });
                sender.sendMessage(Msg.footer("解除 §f/" + CMD + " qqunban <QQ号>"));
            }
            case RELOAD -> {
                plugin.reloadAll();
                sender.sendMessage(Msg.ok("配置已重载"));
                sender.sendMessage(Msg.footer("连接参数（mode / 监听端口 / token）需重启服务器生效"));
            }
            case DIAG -> {
                var cfg = plugin.getConfig();
                var st = endpoint.status();
                var groups = cfg.getStringList("groups.allowed");
                sender.sendMessage(Msg.header("运行时自检"));
                sender.sendMessage(Msg.field("版本", plugin.getPluginMeta().getVersion()
                        + " §8| 配置版本 " + cfg.getInt("config-version", 0)));
                sender.sendMessage(Msg.field("开关", "op-skip=" + plugin.joinListenerSkip()
                        + " §8| self-unbind=" + binds.settings().selfUnbind
                        + " §8| private-bind=" + cfg.getBoolean("private.allow-bind", false)));
                sender.sendMessage(Msg.field("限额", binds.settings().maxPerQq + "/QQ §8| "
                        + binds.settings().maxPerPlayer + "/玩家 §8| "
                        + cfg.getString("bind.limit-policy", "reject")
                        + " §8| 冷却 " + binds.settings().cooldownSeconds + "s"
                        + " §8| 码长 " + binds.settings().codeLength
                        + " §8| 有效期 " + Math.max(1, cfg.getInt("bind.expire-minutes", 5)) + "m"));
                sender.sendMessage(Msg.field("群", "白名单 " + groups.size() + " 个"
                        + (groups.isEmpty() ? " §c(空!)" : "")
                        + " §8| allow-all=" + cfg.getBoolean("groups.allow-all", false)
                        + " §8| 推荐群=" + (cfg.getString("groups.recommended", "").isEmpty() ? "无" : "已设")));
                sender.sendMessage(Msg.field("连接", st.mode() + " "
                        + cfg.getString("onebot.listen-host", "0.0.0.0")
                        + ":" + cfg.getInt("onebot.listen-port", 6700)
                        + " §8| " + (st.connected() ? "§aconnected" : "§cdisconnected")
                        + " §8| self_id=" + st.selfId()
                        + " §8| token=" + (cfg.getString("onebot.access-token", "").isEmpty()
                        ? "§c未设置" : "§a已设置")));
                sender.sendMessage(Msg.field("数据", "binds=" + binds.allBindings().size()
                        + " §8| qqbans=" + binds.store().bannedQqs().size()
                        + " §8| active_codes=" + binds.activeCodeCount()
                        + " §8| admins=" + cfg.getStringList("admins.qq").size()));
                var dataDir = plugin.getDataFolder().toPath();
                sender.sendMessage(Msg.field("文件", "bindings.json "
                        + (java.nio.file.Files.isWritable(dataDir.resolve("bindings.json")) ? "§a可写✓" : "§c不可写✗")
                        + " §8| banned_qqs.json "
                        + (java.nio.file.Files.isWritable(dataDir.resolve("banned_qqs.json")) ? "§a可写✓" : "§c不可写✗")));
                if (!st.connected()) {
                    sender.sendMessage(Msg.warn("§c机器人未连接——绑定指令不会有响应，检查 NapCat 反向WS地址/端口/网络"));
                }
                if (groups.isEmpty() && !cfg.getBoolean("groups.allow-all", false)) {
                    sender.sendMessage(Msg.warn("§c群白名单为空且未开 allow-all——所有群内指令会被静默忽略"));
                }
                if (cfg.getStringList("admins.qq").isEmpty()) {
                    sender.sendMessage(Msg.warn("§eadmins.qq 为空——群内无人能用 #解绑/#拉黑 等管理指令"));
                }
            }
            // 新增子指令忘了接线也不会静默：落到帮助
            default -> sendHelp(sender);
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String fmtTime(long epochMilli) {
        return FMT.format(java.time.Instant.ofEpochMilli(epochMilli)
                .atZone(java.time.ZoneId.systemDefault()));
    }

    /** 容忍手改/历史格式的时间戳字段，不让一条脏数据炸掉整个列表。 */
    private static String fmtTimeSafe(String epochMilli) {
        try {
            return fmtTime(Long.parseLong(epochMilli));
        } catch (RuntimeException e) {
            return "时间未知";
        }
    }

    /** 秒 -> 人类可读时长。 */
    static String fmtDur(long seconds) {
        if (seconds < 0) return "未知";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60 == 0 ? "" : (seconds % 60) + "s");
        if (seconds < 86400) return (seconds / 3600) + "h" + (seconds % 3600 / 60 == 0 ? "" : (seconds % 3600 / 60) + "m");
        return (seconds / 86400) + "d" + (seconds % 86400 / 3600 == 0 ? "" : (seconds % 86400 / 3600) + "h");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Msg.header("管理员指令"));
        for (AdminSub s : AdminSub.values()) {
            sender.sendMessage(Msg.cmdRow(CMD + " " + s.token(), s.args(), s.desc()));
        }
        sender.sendMessage(Msg.footer("§f<必填> §7/ §8[可选] §7· Tab 可补全 · 玩家指令 §f/qqgate"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (AdminSub s : AdminSub.values()) {
                if (s.token().startsWith(prefix)) out.add(s.token());
            }
            return out;
        }
        if (args.length == 2) {
            AdminSub sub = AdminSub.of(args[0]).orElse(null);
            if (sub == AdminSub.LOOKUP || sub == AdminSub.UNBIND || sub == AdminSub.UNBINDALL
                    || sub == AdminSub.BIND) {
                return null; // 交给服务端补全在线玩家名
            }
            if (sub == AdminSub.QQUNBAN) {
                String prefix = args[1];
                return binds.store().bannedQqs().keySet().stream()
                        .map(String::valueOf)
                        .filter(s -> s.startsWith(prefix))
                        .toList();
            }
        }
        return List.of();
    }
}
