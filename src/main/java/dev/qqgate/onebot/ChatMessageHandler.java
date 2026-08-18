package dev.qqgate.onebot;

import dev.qqgate.BotConfig;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * 聊天指令处理（群 + 私聊通道，统一入口）。
 *
 * ── 玩家指令 ──
 *   绑定 <码>        绑定自己（4~8 位数字；空格/全角空格/冒号可选，可紧贴）
 *   查询             列出自己的绑定
 *   解绑             列出自己的绑定（不执行解绑）
 *   解绑 <账号名>     精确解绑自己名下该账号（只影响自己，不可越权）
 *   帮助             指令列表
 *
 * ── 管理员指令（发送者须在 admins.qq 白名单）──
 *   查 <玩家名|QQ号>       双向查询绑定
 *   解绑 <玩家名|QQ号>     目标单条直解；多条时列出并引导精确/全解
 *   解绑 <玩家名> <QQ号>   精确解绑一条
 *   全解绑 <玩家名|QQ号>   清空目标名下全部绑定
 *   绑定 <玩家名> <QQ号>   代绑（跳过验证码，仍走限额裁决；要求玩家已有
 *                          绑定记录以定位 UUID，否则提示走游戏内命令）
 *   状态                   连接状态与统计
 *   帮助                   管理员指令列表
 *
 * ── 开关链（谁能用、在哪用）──
 *   群内一切指令：须在 groups.allowed 白名单群（或 groups.allow-all: true）。
 *   私聊玩家指令：private.allow-bind（默认 false）——管绑定/查询/解绑/帮助全部。
 *   解绑功能：bind.self-unbind（默认 false）——群+私聊一起管；关着时解绑静默忽略。
 *   管理员指令：admins.respond.group / .private（默认均 true）；
 *     私聊时管理员不受 private.allow-bind 限制（旁路放行）。
 *
 * ── 分流规则 ──
 * 先匹配管理员专属语法（查/全解绑/状态/三段式绑定）；「解绑 <目标>」按参数
 * 形态分流：纯数字(QQ)或双参走管理路径，纯玩家名落回玩家路径（只解自己）。
 * 管理员发「绑定 <纯数字>」仍是给自己绑定。非白名单 QQ 发管理员语法按普通
 * 玩家消息处理，无任何权限提升。
 */
public final class ChatMessageHandler implements OneBotEndpoint.MessageListener {

    /** 「绑定」+ 4~8 位数字，分隔符允许空格/全角空格/冒号，或紧贴。 */
    static final Pattern BIND = Pattern.compile(
            "^[\\s\\u3000]*绑定[\\s\\u3000:：]*(\\d{4,8})[\\s\\u3000]*$");
    /** 管理员代绑：绑定 <玩家名> <QQ号>。 */
    static final Pattern ADMIN_BIND = Pattern.compile(
            "^[\\s\\u3000]*绑定[\\s\\u3000]+(\\S+?)[\\s\\u3000]+(\\d{5,12})[\\s\\u3000]*$");
    /** 解绑（无参=列表）与带账号名。 */
    static final Pattern UNBIND = Pattern.compile("^[\\s\\u3000]*解绑(?:[\\s\\u3000]+(\\S+?)(?:[\\s\\u3000]+(\\d{5,12}))?)?[\\s\\u3000]*$");
    /** 全解绑 <目标>。 */
    static final Pattern UNBIND_ALL = Pattern.compile("^[\\s\\u3000]*全解绑[\\s\\u3000]+(\\S+?)[\\s\\u3000]*$");
    /** 管理员查 <目标>。 */
    static final Pattern ADMIN_LOOKUP = Pattern.compile("^[\\s\\u3000]*查[\\s\\u3000]+(\\S+?)[\\s\\u3000]*$");
    static final Pattern QUERY = Pattern.compile("^[\\s\\u3000]*查询[\\s\\u3000]*$");
    static final Pattern HELP = Pattern.compile("^[\\s\\u3000]*帮助[\\s\\u3000]*$");
    static final Pattern STATUS = Pattern.compile("^[\\s\\u3000]*状态[\\s\\u3000]*$");
    /** 剥离 CQ 码。 */
    static final Pattern CQ = Pattern.compile("\\[CQ:[^\\]]*]");

    private static final java.time.format.DateTimeFormatter TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final BotConfig config;
    private final BindService binds;
    private final OneBotEndpoint endpoint;
    private final Logger log;

    public ChatMessageHandler(BotConfig config, BindService binds, OneBotEndpoint endpoint, Logger log) {
        this.config = config;
        this.binds = binds;
        this.endpoint = endpoint;
        this.log = log;
    }

    @Override
    public void onMessage(OneBotEndpoint.IncomingMessage msg) {
        if (msg.isGroup()) {
            if (!groupAllowed(msg.groupId())) return;
        } else if (!privateOpenFor(msg.userId())) {
            return; // 私聊：玩家通道与管理员通道都未开
        }

        String text = CQ.matcher(msg.rawMessage()).replaceAll("").trim();
        long now = System.currentTimeMillis();

        // ---- 管理员语法优先 ----
        if (handleAdminSyntax(msg, text)) return;

        // ---- 玩家路径 ----
        Matcher m = BIND.matcher(text);
        if (m.matches()) {
            handleBind(msg, m.group(1), now);
            return;
        }
        m = UNBIND.matcher(text);
        if (m.matches()) {
            handleUnbind(msg, m.group(1));
            return;
        }
        if (QUERY.matcher(text).matches()) {
            handleQuery(msg);
            return;
        }
        if (HELP.matcher(text).matches()) {
            reply(msg, msg("messages.help", defaultHelp()));
        }
    }

    /** 私聊是否开放：玩家开关开，或发送者是管理员且管理私聊通道开。 */
    private boolean privateOpenFor(long qq) {
        if (config.configBool("private.allow-bind", false)) return true;
        return isAdmin(qq) && config.configBool("admins.respond.private", false);
    }

    private boolean groupAllowed(long groupId) {
        if (config.configBool("groups.allow-all", false)) {
            return true;
        }
        for (String g : config.configStringList("groups.allowed")) {
            if (g.trim().equals(String.valueOf(groupId))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdmin(long qq) {
        for (String a : config.configStringList("admins.qq")) {
            if (a.trim().equals(String.valueOf(qq))) return true;
        }
        return false;
    }

    /** 管理员通道检查：群内看 respond.group，私聊看 respond.private。 */
    private boolean adminChannelOpen(OneBotEndpoint.IncomingMessage msg) {
        return msg.isGroup()
                ? config.configBool("admins.respond.group", true)
                : config.configBool("admins.respond.private", true);
    }

    // ================= 管理员指令 =================

    /** 命中管理员语法且身份/通道通过 → 处理并返回 true。 */
    private boolean handleAdminSyntax(OneBotEndpoint.IncomingMessage msg, String text) {
        Matcher m = ADMIN_LOOKUP.matcher(text);
        if (m.matches()) {
            if (adminAllowed(msg)) adminLookup(msg, m.group(1));
            return true;
        }
        m = UNBIND_ALL.matcher(text);
        if (m.matches()) {
            if (adminAllowed(msg)) adminUnbindAll(msg, m.group(1));
            return true;
        }
        if (STATUS.matcher(text).matches()) {
            if (adminAllowed(msg)) adminStatus(msg);
            return true;
        }
        m = ADMIN_BIND.matcher(text);
        if (m.matches()) {
            if (adminAllowed(msg)) adminBind(msg, m.group(1), Long.parseLong(m.group(2)));
            return true;
        }
        // 「解绑 <目标>」形态与玩家「解绑 <账号名>」共词：管理员语法是
        // 解绑<QQ号> 或 解绑<玩家名> <QQ号>；纯账号名落回玩家路径（只解自己）。
        m = UNBIND.matcher(text);
        if (m.matches() && m.group(1) != null && isNumeric(m.group(1))) {
            if (adminAllowed(msg)) adminUnbind(msg, m.group(1), null);
            return true;
        }
        if (m.matches() && m.group(2) != null) {
            if (adminAllowed(msg)) adminUnbind(msg, m.group(1), Long.parseLong(m.group(2)));
            return true;
        }
        if (HELP.matcher(text).matches() && isAdmin(msg.userId())) {
            if (adminAllowed(msg)) {
                // 管理员也是玩家：帮助合并显示玩家段+管理员段（去重 {at}）
                String playerPart = msg("messages.help", defaultHelp());
                String adminPart = msg("messages.admin-help", defaultAdminHelp())
                        .replaceFirst(Pattern.quote("{at}") + "[\\s\\u3000]*", "");
                reply(msg, playerPart + "\n\n" + adminPart);
                return true;
            }
        }
        return false;
    }

    private boolean adminAllowed(OneBotEndpoint.IncomingMessage msg) {
        if (!isAdmin(msg.userId())) return false;
        if (!adminChannelOpen(msg)) return false;
        return true;
    }

    private void adminLookup(OneBotEndpoint.IncomingMessage msg, String target) {
        StringBuilder sb = new StringBuilder();
        if (isNumeric(target)) {
            long qq = Long.parseLong(target);
            List<BindStore.Binding> list = binds.findByQq(qq);
            if (list.isEmpty()) {
                reply(msg, msg("messages.admin-lookup-empty", "QQ {target} 未绑定任何账号")
                        .replace("{target}", target));
                return;
            }
            sb.append("QQ ").append(target).append(" 绑定 ").append(list.size()).append(" 个账号：\n");
            for (BindStore.Binding b : list) {
                sb.append("①".repeat(1), 0, 0); // placeholder, replaced below
                sb.setLength(sb.length() - 0);
                sb.append("  ").append(b.name()).append(" · ").append(fmtTime(b.boundAt())).append('\n');
            }
        } else {
            List<BindStore.Binding> list = binds.allBindings().stream()
                    .filter(b -> b.name().equalsIgnoreCase(target)).toList();
            if (list.isEmpty()) {
                reply(msg, msg("messages.admin-lookup-empty", "玩家 {target} 未绑定QQ")
                        .replace("{target}", target));
                return;
            }
            sb.append("玩家 ").append(target).append(" 绑定 ").append(list.size()).append(" 个QQ：\n");
            for (BindStore.Binding b : list) {
                sb.append("  QQ ").append(b.qq()).append(" · ").append(fmtTime(b.boundAt())).append('\n');
            }
        }
        reply(msg, msg("messages.admin-lookup", "{at} {result}")
                .replace("{result}", sb.toString().stripTrailing()));
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=查 target=" + target);
    }

    private void adminUnbind(OneBotEndpoint.IncomingMessage msg, String target, Long exactQq) {
        // 双参：精确解绑
        if (exactQq != null) {
            var list = binds.allBindings().stream()
                    .filter(b -> b.name().equalsIgnoreCase(target) && b.qq() == exactQq).toList();
            if (list.isEmpty()) {
                reply(msg, msg("messages.admin-unbind-notfound",
                        "{at} 未找到 {player} 与 QQ {qq} 的绑定").replace("{player}", target)
                        .replace("{qq}", String.valueOf(exactQq)));
                return;
            }
            int n = 0;
            for (BindStore.Binding b : list) {
                if (binds.unbindExact(b.uuid(), b.qq())) n++;
            }
            reply(msg, msg("messages.admin-unbind-exact-ok",
                            "{at} 已解绑 {player} <-> QQ {qq}（{player} 还剩 {count} 条绑定）")
                    .replace("{player}", target)
                    .replace("{qq}", String.valueOf(exactQq))
                    .replace("{count}", String.valueOf(binds.allBindings().stream()
                            .filter(b -> b.name().equalsIgnoreCase(target)).count())));
            log.info("[qq-admin] qq=" + msg.userId() + " cmd=解绑(精确) " + target + " " + exactQq);
            return;
        }
        // 单参：按目标类型分流（QQ 全删语义走全解绑；这里单条直解/多条列出）
        if (isNumeric(target)) {
            long qq = Long.parseLong(target);
            List<BindStore.Binding> list = binds.findByQq(qq);
            if (list.isEmpty()) {
                reply(msg, msg("messages.admin-unbind-notfound", "{at} QQ {target} 无绑定")
                        .replace("{target}", target));
                return;
            }
            if (list.size() == 1) {
                binds.unbindExact(list.get(0).uuid(), qq);
                reply(msg, msg("messages.admin-unbind-exact-ok",
                                "{at} 已解绑 {player} <-> QQ {qq}")
                        .replace("{player}", list.get(0).name())
                        .replace("{qq}", target)
                        .replace("{count}", "0"));
                log.info("[qq-admin] qq=" + msg.userId() + " cmd=解绑 target=" + target);
                return;
            }
            replyAmbiguous(msg, "QQ " + target, list);
            return;
        }
        // 玩家名形态
        var list = binds.allBindings().stream()
                .filter(b -> b.name().equalsIgnoreCase(target)).toList();
        if (list.isEmpty()) {
            reply(msg, msg("messages.admin-unbind-notfound", "{at} 玩家 {target} 无绑定")
                    .replace("{target}", target));
            return;
        }
        if (list.size() == 1) {
            binds.unbindExact(list.get(0).uuid(), list.get(0).qq());
            reply(msg, msg("messages.admin-unbind-exact-ok",
                            "{at} 已解绑 {player} <-> QQ {qq}")
                    .replace("{player}", target)
                    .replace("{qq}", String.valueOf(list.get(0).qq()))
                    .replace("{count}", "0"));
            log.info("[qq-admin] qq=" + msg.userId() + " cmd=解绑 target=" + target);
            return;
        }
        replyAmbiguous(msg, target, list);
    }

    private void replyAmbiguous(OneBotEndpoint.IncomingMessage msg, String label, List<BindStore.Binding> list) {
        StringBuilder sb = new StringBuilder(label).append(" 名下有 ").append(list.size()).append(" 条绑定：\n");
        int i = 1;
        for (BindStore.Binding b : list) {
            sb.append(' ').append(i++).append(". QQ ").append(b.qq())
                    .append("（").append(b.name()).append("）\n");
        }
        sb.append("精确解绑：解绑 <玩家名> <QQ号>\n清空全部：全解绑 <目标>");
        reply(msg, msg("messages.admin-unbind-ambiguous", "{at} {result}")
                .replace("{result}", sb.toString()));
    }

    private void adminUnbindAll(OneBotEndpoint.IncomingMessage msg, String target) {
        int n;
        String detail;
        if (isNumeric(target)) {
            long qq = Long.parseLong(target);
            var list = binds.findByQq(qq);
            n = binds.unbindQq(qq);
            detail = list.stream().map(BindStore.Binding::name)
                    .reduce((a, b) -> a + "、" + b).orElse("");
        } else {
            var list = binds.allBindings().stream()
                    .filter(b -> b.name().equalsIgnoreCase(target)).toList();
            n = 0;
            for (BindStore.Binding b : list) {
                if (binds.unbindExact(b.uuid(), b.qq())) n++;
            }
            detail = list.stream().map(b -> String.valueOf(b.qq()))
                    .reduce((a, b) -> a + "、" + b).orElse("");
        }
        reply(msg, (n > 0
                ? msg("messages.admin-unbindall-ok", "{at} 已清空 {target} 名下 {count} 条绑定（{detail}）")
                : msg("messages.admin-unbind-notfound", "{at} {target} 无绑定"))
                .replace("{target}", target)
                .replace("{count}", String.valueOf(n))
                .replace("{detail}", detail));
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=全解绑 target=" + target + " n=" + n);
    }

    private void adminBind(OneBotEndpoint.IncomingMessage msg, String name, long qq) {
        // 名字解析：离线名无 UUID 来源，直接以名字作展示锚（与代绑语义一致，走全量检索）
        var existing = binds.allBindings().stream()
                .filter(b -> b.name().equalsIgnoreCase(name) && b.qq() == qq).toList();
        if (!existing.isEmpty()) {
            reply(msg, msg("messages.already-bound", "{at} 该QQ已绑定游戏账号 {player}，无需重复绑定")
                    .replace("{player}", name));
            return;
        }
        // 管理员代绑需 uuid；QQ 侧无法解析离线名 → 用 name 字段哈希稳定 uuid 不安全，
        // 因此 QQ 侧代绑仅在目标已有任意绑定时可定位 uuid；否则提示走游戏内命令。
        var byName = binds.allBindings().stream()
                .filter(b -> b.name().equalsIgnoreCase(name)).toList();
        if (byName.isEmpty()) {
            reply(msg, msg("messages.admin-bind-no-player",
                    "{at} 未找到玩家 {player} 的既有绑定记录，无法定位UUID；请让玩家先进服一次，或使用游戏内 /qqgateadmin bind"));
            return;
        }
        var r = binds.adminBind(byName.get(0).uuid(), name, qq, System.currentTimeMillis());
        String reply = switch (r.outcome()) {
            case SUCCESS, SUCCESS_REPLACED -> msg("messages.admin-bind-ok",
                    "{at} 已绑定 {player} <-> QQ {qq}" + (r.evicted() != null ? "（挤下 " + r.evicted().name() + "）" : ""));
            case QQ_FULL -> msg("messages.qq-full", "{at} 该QQ已绑定满 {max} 个账号（{count}/{max}），请联系管理员");
            case PLAYER_FULL -> msg("messages.player-full", "{at} 该游戏账号已绑定满 {max} 个QQ，请联系管理员");
            case ALREADY_BOUND -> msg("messages.already-bound", "{at} 该QQ已绑定游戏账号 {player}，无需重复绑定");
            default -> msg("messages.admin-bind-fail", "{at} 绑定失败: {reason}");
        };
        reply(msg, reply
                .replace("{player}", name)
                .replace("{qq}", String.valueOf(qq))
                .replace("{max}", String.valueOf(binds.settings().maxPerQq))
                .replace("{count}", String.valueOf(binds.findByQq(qq).size())));
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=绑定 " + name + " " + qq + " -> " + r.outcome());
    }

    private void adminStatus(OneBotEndpoint.IncomingMessage msg) {
        var st = endpoint.status();
        String s = "mode=" + st.mode() + " connected=" + st.connected()
                + " self_id=" + st.selfId()
                + " binds=" + binds.allBindings().size()
                + " active_codes=" + binds.activeCodeCount();
        reply(msg, msg("messages.admin-status", "{at} {result}").replace("{result}", s));
    }

    // ================= 玩家指令 =================

    private void handleBind(OneBotEndpoint.IncomingMessage msg, String code, long now) {
        BindService.BindResult r = binds.attemptBind(code, msg.userId(), now);
        int max = binds.settings().maxPerQq;
        int count = binds.findByQq(msg.userId()).size();
        long remaining = Math.max(0, max - count);
        String reply = switch (r.outcome()) {
            case SUCCESS -> msg("messages.bound", """
                            {at} 绑定成功！
                            游戏账号：{player}
                            该QQ当前已绑定 {count}/{max} 个账号，还可绑定 {remaining} 个
                            现在可以重新进入服务器了""")
                    .replace("{player}", name(r))
                    .replace("{count}", String.valueOf(count))
                    .replace("{max}", String.valueOf(max))
                    .replace("{remaining}", String.valueOf(remaining));
            case SUCCESS_REPLACED -> msg("messages.replaced", """
                            {at} 绑定成功（已自动替换旧绑定，挤下：{old_player}）
                            游戏账号：{player}
                            该QQ当前已绑定 {count}/{max} 个账号，还可绑定 {remaining} 个""")
                    .replace("{old_player}", r.evicted() == null ? "?" : r.evicted().name())
                    .replace("{player}", name(r))
                    .replace("{count}", String.valueOf(count))
                    .replace("{max}", String.valueOf(max))
                    .replace("{remaining}", String.valueOf(remaining));
            case ALREADY_BOUND -> msg("messages.already-bound",
                            "{at} 该QQ已绑定游戏账号 {player}，无需重复绑定")
                    .replace("{player}", name(r));
            case WRONG_CODE -> msg("messages.wrong-code", "{at} 验证码错误或已过期，请重新进入服务器获取");
            case CODE_USED -> msg("messages.code-used", "{at} 该验证码已被使用");
            case QQ_FULL -> msg("messages.qq-full",
                            "{at} 该QQ已绑定满 {max} 个账号（{count}/{max}），请联系管理员")
                    .replace("{max}", String.valueOf(max))
                    .replace("{count}", String.valueOf(count));
            case PLAYER_FULL -> msg("messages.player-full",
                            "{at} 该游戏账号已绑定满 {max} 个QQ，请联系管理员")
                    .replace("{max}", String.valueOf(binds.settings().maxPerPlayer));
            case COOLDOWN -> msg("messages.cooldown", "{at} 操作太频繁，请 {seconds} 秒后再试")
                    .replace("{seconds}", String.valueOf(r.retryAfterSeconds()));
        };
        reply(msg, reply);
        if (r.outcome() == BindService.Outcome.SUCCESS
                || r.outcome() == BindService.Outcome.SUCCESS_REPLACED) {
            log.info(String.format("[bind] %s qq=%d player=%s code=%s (%d/%d)",
                    msg.isGroup() ? "group:" + msg.groupId() : "private",
                    msg.userId(), name(r), code, count, max));
        }
    }

    /** 玩家解绑：带参=精确解自己名下该账号；无参=列出绑定列表。 */
    private void handleUnbind(OneBotEndpoint.IncomingMessage msg, String accountName) {
        if (!binds.settings().selfUnbind) {
            return; // 未开启自助解绑：静默忽略
        }
        if (accountName == null) {
            listMine(msg);
            return;
        }
        int n = binds.selfUnbindByName(msg.userId(), accountName);
        if (n > 0) {
            int remaining = binds.findByQq(msg.userId()).size();
            reply(msg, msg("messages.self-unbind-ok",
                            "{at} 已解绑账号 {player}（该QQ还剩 {count} 个绑定）")
                    .replace("{player}", accountName)
                    .replace("{count}", String.valueOf(remaining)));
            log.info("[unbind-self] qq=" + msg.userId() + " player=" + accountName
                    + " remaining=" + remaining);
        } else {
            reply(msg, msg("messages.self-unbind-notfound",
                            "{at} 你名下没有名为 {player} 的绑定，发送「解绑」查看列表")
                    .replace("{player}", accountName));
        }
    }

    /** 玩家查询：列出自己的绑定。 */
    private void handleQuery(OneBotEndpoint.IncomingMessage msg) {
        List<BindStore.Binding> mine = binds.findByQq(msg.userId());
        if (mine.isEmpty()) {
            reply(msg, msg("messages.not-bound", "{at} 你当前没有绑定任何账号"));
            return;
        }
        listMine(msg);
    }

    private void listMine(OneBotEndpoint.IncomingMessage msg) {
        List<BindStore.Binding> mine = binds.findByQq(msg.userId());
        if (mine.isEmpty()) {
            reply(msg, msg("messages.not-bound", "{at} 你当前没有绑定任何账号"));
            return;
        }
        StringBuilder sb = new StringBuilder("已绑定 ").append(mine.size()).append("/")
                .append(binds.settings().maxPerQq).append(" 个账号：\n");
        int i = 1;
        for (BindStore.Binding b : mine) {
            sb.append(' ').append(i++).append(". ").append(b.name())
                    .append("（").append(fmtTime(b.boundAt())).append("）\n");
        }
        if (binds.settings().selfUnbind) {
            sb.append("解绑指定账号：解绑 <账号名>");
        }
        reply(msg, msg("messages.self-unbind-list", "{at} {result}")
                .replace("{result}", sb.toString().stripTrailing()));
    }

    // ================= 通用 =================

    /** 回执：群 → 引用原消息 + @发送者 + 换行 + 正文；私聊 → 纯文本私聊。 */
    private void reply(OneBotEndpoint.IncomingMessage msg, String text) {
        if (msg.isGroup()) {
            StringBuilder out = new StringBuilder();
            if (msg.messageId() != 0) {
                out.append("[CQ:reply,id=").append(msg.messageId()).append(']');
            }
            String at = "[CQ:at,qq=" + msg.userId() + "]\n";
            String body = text.replace("{at}", at).replace("{qq}", String.valueOf(msg.userId()));
            body = body.replace("\\n", "\n");
            endpoint.sendGroupMessage(msg.groupId(), out.append(body).toString());
        } else {
            String body = text.replace("{at}", "").replace("{qq}", String.valueOf(msg.userId()));
            endpoint.sendPrivateMessage(msg.userId(), body.replace("\\n", "\n").stripLeading());
        }
    }

    private String msg(String path, String def) {
        return config.configString(path, def);
    }

    private static String name(BindService.BindResult r) {
        return r.created() == null ? "?" : r.created().name();
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static String fmtTime(long epochMilli) {
        return TIME_FMT.format(java.time.Instant.ofEpochMilli(epochMilli)
                .atZone(java.time.ZoneId.systemDefault()));
    }

    private static String defaultHelp() {
        return """
                {at} 可用指令：
                绑定 <验证码> —— 绑定游戏账号
                查询 —— 查看我的绑定"""
                + ("\n解绑 <账号名> —— 解绑指定账号（需管理员开启）");
    }

    private static String defaultAdminHelp() {
        return """
                {at} 管理员指令：
                查 <玩家名|QQ号> —— 查询绑定
                解绑 <玩家名|QQ号> —— 解绑（多条会列出）
                解绑 <玩家名> <QQ号> —— 精确解绑
                全解绑 <玩家名|QQ号> —— 清空全部绑定
                绑定 <玩家名> <QQ号> —— 代绑
                状态 —— 连接与统计""";
    }
}
