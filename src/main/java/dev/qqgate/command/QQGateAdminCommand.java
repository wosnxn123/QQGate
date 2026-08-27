package dev.qqgate.command;

import dev.qqgate.QQGatePlugin;
import dev.qqgate.admin.AdminOps;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;
import dev.qqgate.onebot.OneBotEndpoint;
import dev.qqgate.util.QqId;
import dev.qqgate.util.TimeFmt;
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
 * <p>业务判断（目标解析、歧义、UUID 回退、计数）全部委托 {@link AdminOps}，本类只把
 * 结果 record 渲染成聊天栏文案；QQ 号闸门统一走 {@link QqId#parse}（超长纯数字
 * 落玩家名而非抛数值解析异常），时间显示统一走 {@link TimeFmt} 预设
 * （模式串/时区配置与降级都在其内部）。
 */
public final class QQGateAdminCommand implements CommandExecutor, TabCompleter {

    /** 帮助与用法提示里显示的命令本体。 */
    private static final String CMD = "qqgateadmin";

    private final QQGatePlugin plugin;
    private final BindService binds;
    private final OneBotEndpoint endpoint;
    /** 业务编排单入口：判定逻辑不在本类重复实现，只渲染结果（见类注释）。 */
    private final AdminOps ops;

    public QQGateAdminCommand(QQGatePlugin plugin, BindService binds, OneBotEndpoint endpoint) {
        this.plugin = plugin;
        this.binds = binds;
        this.endpoint = endpoint;
        this.ops = new AdminOps(binds);
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
                AdminOps.StatusCounts counts = ops.statusCounts();
                sender.sendMessage(Msg.header("连接状态"));
                sender.sendMessage(Msg.field("模式", st.mode()));
                sender.sendMessage(Msg.field("连接", st.connected() ? "§a已连接 ✓" : "§c未连接 ✗"));
                sender.sendMessage(Msg.field("机器人QQ", st.selfId() > 0 ? String.valueOf(st.selfId()) : "§7未上报"));
                sender.sendMessage(Msg.field("已连接", fmtDur(st.connectedSeconds())));
                sender.sendMessage(Msg.field("最近事件", st.lastEventSecondsAgo() < 0
                        ? "§7从未" : fmtDur(st.lastEventSecondsAgo()) + " 前"));
                sender.sendMessage(Msg.field("绑定", counts.bindings() + " 条"
                        + " §8| 黑名单 " + counts.banned() + " 条"));
                sender.sendMessage(Msg.field("待验证码", counts.activeCodes() + " 个"));
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
                AdminOps.Target target = AdminOps.target(q);
                AdminOps.LookupResult r = ops.lookup(target, resolveUuid(target));
                r.ban().ifPresent(ban -> sender.sendMessage(Msg.warn("QQ " + ban.qq() + " 已被拉黑"
                        + (ban.reason().isEmpty() ? "" : " §7原因: §f" + ban.reason())
                        + " §8· §7" + TimeFmt.formatSafe(TimeFmt.Preset.LIST, ban.bannedAtRaw(),
                                plugin, plugin.getLogger()::warning))));
                switch (target) {
                    case AdminOps.QqTarget t -> {
                        if (r.bindings().isEmpty()) {
                            sender.sendMessage(Msg.info("QQ " + t.qq() + " 未绑定任何账号"));
                        } else {
                            sender.sendMessage(Msg.title("QQ §e" + t.qq() + " §f绑定 "
                                    + r.bindings().size() + " 个账号："));
                            for (BindStore.Binding b : r.bindings()) {
                                sender.sendMessage(Msg.item(b.name(), TimeFmt.format(TimeFmt.Preset.LIST,
                                        b.boundAt(), plugin, plugin.getLogger()::warning)));
                            }
                        }
                    }
                    case AdminOps.NameTarget n -> {
                        if (r.bindings().isEmpty()) {
                            sender.sendMessage(Msg.info("玩家 " + n.name() + " 未绑定QQ（UUID 与名字均未命中）"));
                        } else {
                            boolean byName = r.resolution() == AdminOps.LookupResult.Resolution.BY_NAME;
                            sender.sendMessage(Msg.title("玩家 §e" + n.name() + " §f"
                                    + (byName ? "按名字找到 " : "绑定 ") + r.bindings().size()
                                    + (byName ? " 条：" : " 个QQ：")));
                            for (BindStore.Binding b : r.bindings()) {
                                sender.sendMessage(Msg.item("QQ " + b.qq(), TimeFmt.format(TimeFmt.Preset.LIST,
                                        b.boundAt(), plugin, plugin.getLogger()::warning)));
                            }
                            if (byName) {
                                sender.sendMessage(Msg.footer("UUID 未命中，可能是改名后的历史绑定"));
                            }
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
                    Optional<QqId> parsedQq = QqId.parse(args[2]);
                    if (parsedQq.isEmpty()) {
                        sender.sendMessage(Msg.err("第二个参数必须是QQ号：§f" + args[2]));
                        sender.sendMessage(Msg.usage(CMD + " unbind", AdminSub.UNBIND.args()));
                        return true;
                    }
                    AdminOps.ExactUnbindResult r = ops.unbindExact(q, parsedQq.get());
                    sender.sendMessage(switch (r) {
                        case AdminOps.ExactUnbindResult.NotFound nf ->
                                Msg.info("未找到 " + nf.player() + " 与 QQ " + nf.qq() + " 的绑定");
                        case AdminOps.ExactUnbindResult.Removed rm ->
                                Msg.ok("已解绑 §f" + rm.player() + " §a<-> QQ §f" + rm.qq()
                                        + " §7（该玩家还剩 " + rm.remaining() + " 条）");
                    });
                    return true;
                }
                AdminOps.Target target = AdminOps.target(q);
                AdminOps.UnbindResult r = ops.unbind(target, resolveUuid(target));
                switch (r) {
                    case AdminOps.UnbindResult.NoBinding nb ->
                            sender.sendMessage(Msg.info(noBindingLine(nb.target())));
                    case AdminOps.UnbindResult.Single s ->
                            sender.sendMessage(Msg.ok("已解绑 §f" + s.removed().name()
                                    + " §a<-> QQ §f" + s.removed().qq()
                                    + (s.remaining() > 0 ? " §7（名下还剩 " + s.remaining() + " 条）" : "")));
                    case AdminOps.UnbindResult.Ambiguous a -> {
                        boolean isQq = a.target() instanceof AdminOps.QqTarget;
                        String who = isQq
                                ? "QQ §e" + ((AdminOps.QqTarget) a.target()).qq()
                                : "玩家 §e" + ((AdminOps.NameTarget) a.target()).name();
                        String self = isQq
                                ? ((AdminOps.QqTarget) a.target()).qq().toString()
                                : ((AdminOps.NameTarget) a.target()).name();
                        sender.sendMessage(Msg.title(who + " §f名下有 " + a.candidates().size() + " 条绑定："));
                        int i = 1;
                        for (BindStore.Binding b : a.candidates()) {
                            sender.sendMessage(Msg.item(i++, isQq ? b.name() : "QQ " + b.qq(),
                                    TimeFmt.format(TimeFmt.Preset.LIST, b.boundAt(),
                                            plugin, plugin.getLogger()::warning)));
                        }
                        sender.sendMessage(Msg.hint("精确解绑 §f/" + CMD + " unbind "
                                + (isQq ? "<玩家名> " + self : self + " <QQ号>")));
                        sender.sendMessage(Msg.hint("全部清空 §f/" + CMD + " unbindall " + self));
                    }
                    case AdminOps.UnbindResult.ByUuid bu ->
                            sender.sendMessage(Msg.ok("已按UUID解绑玩家 §f" + q
                                    + " §a的 " + bu.removed() + " 条绑定 §7（可能已改名）"));
                }
            }
            case UNBINDALL -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " unbindall", AdminSub.UNBINDALL.args()));
                    return true;
                }
                AdminOps.Target target = AdminOps.target(args[1]);
                AdminOps.UnbindAllResult r = ops.unbindAll(target, resolveUuid(target));
                if (r.removed() == 0) {
                    sender.sendMessage(Msg.info(noBindingLine(r.target())));
                } else {
                    String detail = r.details().isEmpty()
                            ? "" : " §7（" + String.join("、", r.details()) + "）";
                    sender.sendMessage(Msg.ok(switch (r.target()) {
                        case AdminOps.QqTarget t -> "已清空 QQ §f" + t.qq();
                        case AdminOps.NameTarget n -> "已清空玩家 §f" + n.name();
                    } + " §a名下 " + r.removed() + " 条绑定" + detail));
                }
            }
            case BIND -> {
                if (args.length < 3) {
                    sender.sendMessage(Msg.usage(CMD + " bind", AdminSub.BIND.args()));
                    return true;
                }
                String name = args[1];
                Optional<QqId> parsedQq = QqId.parse(args[2]);
                if (parsedQq.isEmpty()) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[2]));
                    return true;
                }
                QqId qq = parsedQq.get();
                OfflinePlayer p = plugin.getServer().getOfflinePlayer(name);
                UUID uuid = p.getUniqueId();
                // 管理员代绑视为信任提交（跳过验证码），限额/黑名单仍由 BindService 裁决
                AdminOps.AdminBindResult r = ops.adminBind(uuid, name, qq, System.currentTimeMillis());
                sender.sendMessage(switch (r.outcome()) {
                    case SUCCESS -> Msg.ok("已绑定 §f" + name + " §a<-> QQ §f" + qq);
                    case SUCCESS_REPLACED -> Msg.ok("已绑定 §f" + name + " §a<-> QQ §f" + qq
                            + r.evicted().map(ev -> " §7（挤下最旧的 " + ev + "）").orElse(""));
                    case ALREADY_BOUND -> Msg.info(name + " 已绑定 QQ " + qq + "，无需重复操作");
                    case QQ_FULL -> Msg.err("QQ " + qq + " 已绑满 " + r.maxPerQq() + " 个账号（limit-policy=reject）");
                    case PLAYER_FULL -> Msg.err("玩家 " + name + " 已绑满 " + r.maxPerPlayer() + " 个QQ（limit-policy=reject）");
                    case QQ_BANNED -> Msg.err("QQ " + qq + " 在黑名单中，先执行 §f/" + CMD + " qqunban " + qq);
                    // 仅 QQ 侧 adminBindByName 可达（游戏内 UUID 直接解析得出）；为穷举枚举而保留
                    case NO_PLAYER_RECORD -> Msg.err("玩家 " + name + " 无既有绑定记录，无法代绑");
                });
            }
            case QQBAN -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " qqban", AdminSub.QQBAN.args()));
                    return true;
                }
                Optional<QqId> parsedQq = QqId.parse(args[1]);
                if (parsedQq.isEmpty()) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[1]));
                    return true;
                }
                String reason = args.length >= 3
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
                AdminOps.BanResult r = ops.qqban(parsedQq.get(), reason);
                sender.sendMessage(Msg.ok("已拉黑 QQ §f" + r.qq()
                        + (r.reason().isEmpty() ? "" : " §7原因: §f" + r.reason())));
                if (r.blockedNames().isEmpty()) {
                    sender.sendMessage(Msg.footer("该QQ名下无绑定——纯QQ拉黑，不涉及名字封禁"));
                } else {
                    sender.sendMessage(Msg.item("已封锁账号", String.join("、", r.blockedNames())));
                    sender.sendMessage(Msg.footer("绑定保留作案底；同名新连接一律拒绝，解拉黑后自动复原"));
                }
            }
            case QQUNBAN -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.usage(CMD + " qqunban", AdminSub.QQUNBAN.args()));
                    return true;
                }
                Optional<QqId> parsedQq = QqId.parse(args[1]);
                if (parsedQq.isEmpty()) {
                    sender.sendMessage(Msg.err("QQ号格式错误：§f" + args[1]));
                    return true;
                }
                AdminOps.UnbanResult r = ops.qqunban(parsedQq.get());
                sender.sendMessage(r.wasBanned()
                        ? Msg.ok("已解除拉黑 QQ §f" + r.qq() + " §7名下账号自动复原")
                        : Msg.info("QQ " + r.qq() + " 不在黑名单"));
            }
            case QQBANS -> {
                List<AdminOps.BanListEntry> bans = ops.banList();
                if (bans.isEmpty()) {
                    sender.sendMessage(Msg.info("QQ 黑名单为空"));
                    return true;
                }
                sender.sendMessage(Msg.title("QQ 黑名单 §e" + bans.size() + " §f条："));
                for (AdminOps.BanListEntry e : bans) {
                    StringBuilder note = new StringBuilder(TimeFmt.formatSafe(TimeFmt.Preset.LIST,
                            e.ban().bannedAtRaw(), plugin, plugin.getLogger()::warning));
                    if (!e.ban().reason().isEmpty()) note.append(" §8· §7").append(e.ban().reason());
                    if (!e.names().isEmpty()) note.append(" §8· §f名下: ").append(String.join("、", e.names()));
                    sender.sendMessage(Msg.item("QQ " + e.ban().qq(), note.toString()));
                }
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

    /** 名字目标 → Bukkit 解析 UUID（本类唯一碰 Bukkit 的边界）；QQ 目标或解析不了 → null。 */
    private UUID resolveUuid(AdminOps.Target target) {
        if (target instanceof AdminOps.NameTarget n) {
            OfflinePlayer p = plugin.getServer().getOfflinePlayer(n.name());
            return p == null ? null : p.getUniqueId();
        }
        return null;
    }

    /** 「查无绑定」回执：目标称呼不带颜色（info 行整体是灰的）。 */
    private static String noBindingLine(AdminOps.Target target) {
        return switch (target) {
            case AdminOps.QqTarget t -> "QQ " + t.qq() + " 无绑定";
            case AdminOps.NameTarget n -> "玩家 " + n.name() + " 无绑定";
        };
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
