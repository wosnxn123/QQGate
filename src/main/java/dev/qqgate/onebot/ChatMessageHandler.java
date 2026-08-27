package dev.qqgate.onebot;

import dev.qqgate.BotConfig;
import dev.qqgate.admin.AdminOps;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;
import dev.qqgate.util.QqId;
import dev.qqgate.util.TimeFmt;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天指令处理（群 + 私聊通道，统一入口）。
 *
 * <p>文案契约：所有回复文案经 {@link MsgRenderer} 渲染 {@link QqMsg} 键（键路径与
 * 默认文案以 QqMsg 为唯一权威）；管理员分支的业务判定收口在 {@link AdminOps}，本类
 * 只做结果到文案的映射；QQ 号一律经 {@link QqId} 解析；时间展示统一用
 * {@link TimeFmt.Preset#LIST} 预渲染后塞进 {time}。{@code {at}}/{@code {sender}} 由
 * {@link #reply} 统一注入，渲染器不碰；{@code {qq}} 只表示业务目标 QQ。
 *
 * ── 玩家指令 ──
 *   绑定 <码>        绑定自己（4~8 位数字；空格/全角空格/冒号可选，可紧贴）
 *   查询             列出自己的绑定
 *   解绑             列出自己的绑定（不执行解绑）
 *   解绑 <账号名>     精确解绑自己名下该账号（只影响自己，不可越权）
 *   帮助             指令列表
 *
 * ── 管理员指令（发送者须在 admins.qq 白名单）──
 *   查 <玩家名|QQ号>       双向查询绑定（拉黑QQ带⚠标记）
 *   解绑 <玩家名|QQ号>     目标单条直解；多条时列出并引导精确/全解
 *   解绑 <玩家名> <QQ号>   精确解绑一条
 *   全解绑 <玩家名|QQ号>   清空目标名下全部绑定
 *   绑定 <玩家名> <QQ号>   代绑（跳过验证码，仍走限额裁决；要求玩家已有
 *                          绑定记录以定位 UUID，否则提示走游戏内命令）
 *   拉黑 <QQ号> [原因]     拉黑（名下账号封锁，绑定保留作案底）
 *   解拉黑 <QQ号>          解除拉黑（账号自动复原）
 *   拉黑列表               黑名单（含名下账号名）
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
 * 先匹配管理员专属语法（查/全解绑/状态/三段式绑定）；「解绑 <目标>」按参数形态
 * 分流：双参优先走精确解绑；单参纯数字(QQ)走管理路径，纯玩家名落回玩家路径
 * （只解自己）。管理员发「绑定 <纯数字>」仍是给自己绑定。非白名单 QQ 发管理员
 * 语法按普通玩家消息处理，无任何权限提升。
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
    /** 管理员拉黑 <QQ> [原因] / 解拉黑 <QQ> / 拉黑列表。 */
    static final Pattern QQBAN = Pattern.compile(
            "^[\\s\\u3000]*拉黑[\\s\\u3000]+(\\d{5,12})(?:[\\s\\u3000]+(.*?))?[\\s\\u3000]*$");
    static final Pattern QQUNBAN = Pattern.compile("^[\\s\\u3000]*解拉黑[\\s\\u3000]+(\\d{5,12})[\\s\\u3000]*$");
    static final Pattern QQBANS = Pattern.compile("^[\\s\\u3000]*拉黑列表[\\s\\u3000]*$");
    /** 管理员查 <目标>。 */
    static final Pattern ADMIN_LOOKUP = Pattern.compile("^[\\s\\u3000]*查[\\s\\u3000]+(\\S+?)[\\s\\u3000]*$");
    static final Pattern QUERY = Pattern.compile("^[\\s\\u3000]*查询[\\s\\u3000]*$");
    static final Pattern HELP = Pattern.compile("^[\\s\\u3000]*帮助[\\s\\u3000]*$");
    static final Pattern STATUS = Pattern.compile("^[\\s\\u3000]*状态[\\s\\u3000]*$");
    /** 剥离 CQ 码。 */
    static final Pattern CQ = Pattern.compile("\\[CQ:[^\\]]*]");

    private final BotConfig config;
    private final BindService binds;
    private final OneBotEndpoint endpoint;
    private final Logger log;
    private final MsgRenderer renderer;
    private final AdminOps adminOps;

    public ChatMessageHandler(BotConfig config, BindService binds, OneBotEndpoint endpoint, Logger log,
                              MsgRenderer renderer, AdminOps adminOps) {
        this.config = config;
        this.binds = binds;
        this.endpoint = endpoint;
        this.log = log;
        this.renderer = renderer;
        this.adminOps = adminOps;
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
            reply(msg, playerHelp());
        }
    }

    /** 私聊是否开放：玩家开关开，或发送者是管理员且管理私聊通道开。 */
    private boolean privateOpenFor(long qq) {
        if (config.configBool("private.allow-bind", false)) return true;
        // 默认 true：与 adminChannelOpen / config.yml 一致（曾误写 false，管理员私聊指令被静默丢弃）
        return isAdmin(qq) && config.configBool("admins.respond.private", true);
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
        m = QQBAN.matcher(text);
        if (m.matches()) {
            String rawQq = m.group(1);
            String reason = m.group(2);
            if (adminAllowed(msg)) {
                QqId.parse(rawQq).ifPresent(qq -> adminQqBan(msg, qq, reason));
            }
            return true;
        }
        m = QQUNBAN.matcher(text);
        if (m.matches()) {
            String rawQq = m.group(1);
            if (adminAllowed(msg)) {
                QqId.parse(rawQq).ifPresent(qq -> adminQqUnban(msg, qq));
            }
            return true;
        }
        if (STATUS.matcher(text).matches()) {
            if (adminAllowed(msg)) adminStatus(msg);
            return true;
        }
        if (QQBANS.matcher(text).matches()) {
            if (adminAllowed(msg)) adminQqBans(msg);
            return true;
        }
        m = ADMIN_BIND.matcher(text);
        if (m.matches()) {
            String player = m.group(1);
            String rawQq = m.group(2);
            if (adminAllowed(msg)) {
                QqId.parse(rawQq).ifPresent(qq -> adminBind(msg, player, qq));
            }
            return true;
        }
        // 「解绑 <目标>」形态与玩家「解绑 <账号名>」共词：管理员语法是
        // 解绑<玩家名> <QQ号>（精确）或 解绑<QQ号>；纯账号名落回玩家路径（只解自己）。
        m = UNBIND.matcher(text);
        if (m.matches()) {
            if (m.group(2) != null) {
                // 双参优先：「解绑 123 456」是精确解绑玩家「123」与 QQ 456，不是解绑 QQ 123
                String player = m.group(1);
                String rawQq = m.group(2);
                if (adminAllowed(msg)) {
                    QqId.parse(rawQq).ifPresent(qq -> adminUnbindExact(msg, player, qq));
                }
                return true;
            }
            if (m.group(1) != null && QqId.isValid(m.group(1))) {
                if (adminAllowed(msg)) adminUnbind(msg, AdminOps.target(m.group(1)));
                return true;
            }
        }
        if (HELP.matcher(text).matches() && isAdmin(msg.userId())) {
            if (adminAllowed(msg)) {
                // 管理员也是玩家：帮助合并显示玩家段+管理员段（去重 {at}）
                String adminPart = renderer.render(QqMsg.ADMIN_HELP)
                        .replaceFirst(Pattern.quote(QqMsg.Field.AT.token()) + "[\\s\\u3000]*", "");
                reply(msg, playerHelp() + "\n\n" + adminPart);
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

    /** 管理员查询：双向；判定走 AdminOps，本方法只做渲染分发。 */
    private void adminLookup(OneBotEndpoint.IncomingMessage msg, String rawTarget) {
        AdminOps.LookupResult r = adminOps.lookup(AdminOps.target(rawTarget));
        switch (r.target()) {
            case AdminOps.QqTarget t -> lookupQq(msg, t.qq().digits(), r);
            case AdminOps.NameTarget n -> lookupPlayer(msg, n.name(), r);
        }
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=查 target=" + rawTarget);
    }

    /** QQ 方向：拉黑标记可选置顶（无 {at}，作首行），随后空结果或绑定列表。 */
    private void lookupQq(OneBotEndpoint.IncomingMessage msg, String qq, AdminOps.LookupResult r) {
        StringBuilder sb = new StringBuilder();
        r.ban().ifPresent(ban -> sb.append(renderer.render(QqMsg.ADMIN_LOOKUP_BANNED_NOTE,
                Map.of(QqMsg.Field.REASON, reasonNote(ban.reason())))).append('\n'));
        if (r.bindings().isEmpty()) {
            sb.append(renderer.render(QqMsg.ADMIN_LOOKUP_QQ_EMPTY, Map.of(QqMsg.Field.QQ, qq)));
        } else {
            sb.append(renderer.render(QqMsg.ADMIN_LOOKUP_QQ_HEADER, Map.of(
                    QqMsg.Field.QQ, qq,
                    QqMsg.Field.COUNT, String.valueOf(r.bindings().size()))));
            for (BindStore.Binding b : r.bindings()) {
                sb.append('\n').append(renderer.render(QqMsg.ADMIN_LOOKUP_QQ_ITEM, Map.of(
                        QqMsg.Field.PLAYER, b.name(),
                        QqMsg.Field.TIME, fmtListTime(b.boundAt()))));
            }
        }
        reply(msg, sb.toString().stripTrailing());
    }

    /** 玩家名方向：空结果或绑定列表。 */
    private void lookupPlayer(OneBotEndpoint.IncomingMessage msg, String player, AdminOps.LookupResult r) {
        if (r.bindings().isEmpty()) {
            reply(msg, renderer.render(QqMsg.ADMIN_LOOKUP_EMPTY,
                    Map.of(QqMsg.Field.TARGET, player)));
            return;
        }
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.ADMIN_LOOKUP_PLAYER_HEADER, Map.of(
                QqMsg.Field.PLAYER, player,
                QqMsg.Field.COUNT, String.valueOf(r.bindings().size()))));
        for (BindStore.Binding b : r.bindings()) {
            sb.append('\n').append(renderer.render(QqMsg.ADMIN_LOOKUP_PLAYER_ITEM, Map.of(
                    QqMsg.Field.QQ, String.valueOf(b.qq()),
                    QqMsg.Field.TIME, fmtListTime(b.boundAt()))));
        }
        reply(msg, sb.toString().stripTrailing());
    }

    /** 管理员解绑（单参）：QQ 或玩家名目标；判定走 AdminOps，本方法只做渲染。 */
    private void adminUnbind(OneBotEndpoint.IncomingMessage msg, AdminOps.Target target) {
        AdminOps.UnbindResult r = adminOps.unbind(target);
        switch (r) {
            case AdminOps.UnbindResult.NoBinding nb ->
                    reply(msg, renderer.render(QqMsg.ADMIN_UNBIND_NOTFOUND,
                            Map.of(QqMsg.Field.TARGET, targetLabel(nb.target()))));
            case AdminOps.UnbindResult.Single s -> {
                reply(msg, renderer.render(QqMsg.ADMIN_UNBIND_EXACT_OK, Map.of(
                        QqMsg.Field.PLAYER, s.removed().name(),
                        QqMsg.Field.QQ, String.valueOf(s.removed().qq()),
                        QqMsg.Field.COUNT, String.valueOf(s.remaining()))));
                log.info("[qq-admin] qq=" + msg.userId() + " cmd=解绑 target=" + targetLabel(s.target()));
            }
            case AdminOps.UnbindResult.Ambiguous a -> replyAmbiguous(msg, a.target(), a.candidates());
            case AdminOps.UnbindResult.ByUuid u ->
                    // QQ 侧走单参重载、无 UUID 途径，该状态不可达
                    throw new AssertionError("QQ 侧不应产生 ByUuid: " + u);
        }
    }

    /** 目标多条绑定：列出候选，引导精确解绑或全解绑。 */
    private void replyAmbiguous(OneBotEndpoint.IncomingMessage msg, AdminOps.Target target,
                                List<BindStore.Binding> candidates) {
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.ADMIN_UNBIND_AMBIGUOUS_HEADER, Map.of(
                QqMsg.Field.TARGET, targetLabel(target),
                QqMsg.Field.COUNT, String.valueOf(candidates.size()))));
        int i = 1;
        for (BindStore.Binding b : candidates) {
            sb.append('\n').append(renderer.render(QqMsg.ADMIN_UNBIND_AMBIGUOUS_ITEM, Map.of(
                    QqMsg.Field.INDEX, String.valueOf(i++),
                    QqMsg.Field.QQ, String.valueOf(b.qq()),
                    QqMsg.Field.PLAYER, b.name())));
        }
        sb.append('\n').append(renderer.render(QqMsg.ADMIN_UNBIND_AMBIGUOUS_HINT));
        reply(msg, sb.toString().stripTrailing());
    }

    /** 管理员精确解绑：玩家名 + QQ 号对（名字忽略大小写）。 */
    private void adminUnbindExact(OneBotEndpoint.IncomingMessage msg, String player, QqId qq) {
        AdminOps.ExactUnbindResult r = adminOps.unbindExact(player, qq);
        switch (r) {
            case AdminOps.ExactUnbindResult.NotFound nf ->
                    reply(msg, renderer.render(QqMsg.ADMIN_UNBIND_NOTFOUND_EXACT, Map.of(
                            QqMsg.Field.PLAYER, nf.player(),
                            QqMsg.Field.QQ, String.valueOf(nf.qq()))));
            case AdminOps.ExactUnbindResult.Removed ok -> {
                reply(msg, renderer.render(QqMsg.ADMIN_UNBIND_EXACT_OK, Map.of(
                        QqMsg.Field.PLAYER, ok.player(),
                        QqMsg.Field.QQ, String.valueOf(ok.qq()),
                        QqMsg.Field.COUNT, String.valueOf(ok.remaining()))));
                log.info("[qq-admin] qq=" + msg.userId() + " cmd=解绑(精确) " + player + " " + qq);
            }
        }
    }

    /** 管理员全解绑：清空目标名下全部绑定；{target} 传裸输入（与旧行为一致）。 */
    private void adminUnbindAll(OneBotEndpoint.IncomingMessage msg, String rawTarget) {
        AdminOps.UnbindAllResult r = adminOps.unbindAll(AdminOps.target(rawTarget));
        String text = r.removed() > 0
                ? renderer.render(QqMsg.ADMIN_UNBINDALL_OK, Map.of(
                        QqMsg.Field.TARGET, rawTarget,
                        QqMsg.Field.COUNT, String.valueOf(r.removed()),
                        QqMsg.Field.DETAIL, String.join("、", r.details())))
                : renderer.render(QqMsg.ADMIN_UNBIND_NOTFOUND, Map.of(
                        QqMsg.Field.TARGET, rawTarget));
        reply(msg, text);
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=全解绑 target=" + rawTarget + " n=" + r.removed());
    }

    /** 管理员拉黑：名下有账号时条件拼接封锁条款。 */
    private void adminQqBan(OneBotEndpoint.IncomingMessage msg, QqId qq, String reason) {
        AdminOps.BanResult r = adminOps.qqban(qq, reason);
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.QQBAN_OK, Map.of(
                QqMsg.Field.QQ, String.valueOf(r.qq()),
                QqMsg.Field.REASON, reasonNote(r.reason()))));
        if (!r.blockedNames().isEmpty()) {
            sb.append('\n').append(renderer.render(QqMsg.QQBAN_OK_LOCKED,
                    Map.of(QqMsg.Field.NAMES, String.join("、", r.blockedNames()))));
        }
        reply(msg, sb.toString().stripTrailing());
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=拉黑 target=" + r.qq()
                + " reason=" + r.reason() + " names=" + r.blockedNames());
    }

    /** 管理员解拉黑：本就不在黑名单时换文案。 */
    private void adminQqUnban(OneBotEndpoint.IncomingMessage msg, QqId qq) {
        AdminOps.UnbanResult r = adminOps.qqunban(qq);
        reply(msg, renderer.render(r.wasBanned() ? QqMsg.QQUNBAN_OK : QqMsg.QQUNBAN_NONE,
                Map.of(QqMsg.Field.QQ, String.valueOf(r.qq()))));
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=解拉黑 target=" + r.qq() + " ok=" + r.wasBanned());
    }

    /** 管理员拉黑列表：时间戳读自存储文件，用 formatSafe 容忍手改坏数据。 */
    private void adminQqBans(OneBotEndpoint.IncomingMessage msg) {
        List<AdminOps.BanListEntry> bans = adminOps.banList();
        if (bans.isEmpty()) {
            reply(msg, renderer.render(QqMsg.QQBANS_EMPTY));
            return;
        }
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.QQBANS_LIST_HEADER,
                Map.of(QqMsg.Field.COUNT, String.valueOf(bans.size()))));
        for (AdminOps.BanListEntry e : bans) {
            AdminOps.BanInfo ban = e.ban();
            sb.append('\n').append(renderer.render(QqMsg.QQBANS_LIST_ITEM, Map.of(
                    QqMsg.Field.QQ, String.valueOf(ban.qq()),
                    QqMsg.Field.TIME, fmtListTimeSafe(ban.bannedAtRaw()),
                    QqMsg.Field.REASON, ban.reason().isBlank() ? "" : " · " + ban.reason().strip(),
                    QqMsg.Field.NAMES, e.names().isEmpty() ? "" : " · 名下: " + String.join("、", e.names()))));
        }
        reply(msg, sb.toString().stripTrailing());
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=拉黑列表 n=" + bans.size());
    }

    /** 管理员代绑：判定走 AdminOps；QQ 侧无 UUID 来源，用按名重载。 */
    private void adminBind(OneBotEndpoint.IncomingMessage msg, String player, QqId qq) {
        AdminOps.AdminBindResult r = adminOps.adminBindByName(player, qq, System.currentTimeMillis());
        String text = switch (r.outcome()) {
            case SUCCESS, SUCCESS_REPLACED -> renderer.render(QqMsg.ADMIN_BIND_OK, Map.of(
                            QqMsg.Field.PLAYER, r.player(),
                            QqMsg.Field.QQ, String.valueOf(r.qq())))
                    // 「挤下」尾：确有旧绑定被挤下时才拼接
                    + r.evicted().map(old -> "（挤下：" + old + "）").orElse("");
            case ALREADY_BOUND -> renderer.render(QqMsg.ALREADY_BOUND,
                    Map.of(QqMsg.Field.PLAYER, r.player()));
            case QQ_FULL -> renderer.render(QqMsg.QQ_FULL, Map.of(
                    QqMsg.Field.COUNT, String.valueOf(r.qqBindings()),
                    QqMsg.Field.MAX, String.valueOf(r.maxPerQq())));
            case PLAYER_FULL -> renderer.render(QqMsg.PLAYER_FULL,
                    Map.of(QqMsg.Field.MAX, String.valueOf(r.maxPerPlayer())));
            case QQ_BANNED -> renderer.render(QqMsg.ADMIN_BIND_BANNED,
                    Map.of(QqMsg.Field.QQ, String.valueOf(r.qq())));
            case NO_PLAYER_RECORD -> renderer.render(QqMsg.ADMIN_BIND_NO_PLAYER,
                    Map.of(QqMsg.Field.PLAYER, r.player()));
        };
        reply(msg, text);
        log.info("[qq-admin] qq=" + msg.userId() + " cmd=绑定 " + player + " " + qq + " -> " + r.outcome());
    }

    /** 管理员状态：连接信息取自 OneBotEndpoint，绑定/验证码计数走 AdminOps。 */
    private void adminStatus(OneBotEndpoint.IncomingMessage msg) {
        OneBotEndpoint.Status st = endpoint.status();
        AdminOps.StatusCounts counts = adminOps.statusCounts();
        reply(msg, renderer.render(QqMsg.ADMIN_STATUS, Map.of(
                QqMsg.Field.MODE, st.mode(),
                QqMsg.Field.CONNECTED, String.valueOf(st.connected()),
                QqMsg.Field.SELF_ID, String.valueOf(st.selfId()),
                QqMsg.Field.BINDS, String.valueOf(counts.bindings()),
                QqMsg.Field.CODES, String.valueOf(counts.activeCodes()))));
    }

    // ================= 玩家指令 =================

    private void handleBind(OneBotEndpoint.IncomingMessage msg, String code, long now) {
        BindService.BindResult r = binds.attemptBind(code, msg.userId(), now);
        int max = binds.settings().maxPerQq;
        int count = binds.findByQq(msg.userId()).size();
        long remaining = Math.max(0, max - count);
        String text = switch (r.outcome()) {
            case SUCCESS -> renderer.render(QqMsg.BOUND, Map.of(
                    QqMsg.Field.PLAYER, name(r),
                    QqMsg.Field.COUNT, String.valueOf(count),
                    QqMsg.Field.MAX, String.valueOf(max),
                    QqMsg.Field.REMAINING, String.valueOf(remaining)));
            case SUCCESS_REPLACED -> renderer.render(QqMsg.REPLACED, Map.of(
                    QqMsg.Field.OLD_PLAYER, r.evicted() == null ? "?" : r.evicted().name(),
                    QqMsg.Field.PLAYER, name(r),
                    QqMsg.Field.COUNT, String.valueOf(count),
                    QqMsg.Field.MAX, String.valueOf(max),
                    QqMsg.Field.REMAINING, String.valueOf(remaining)));
            case ALREADY_BOUND -> renderer.render(QqMsg.ALREADY_BOUND,
                    Map.of(QqMsg.Field.PLAYER, name(r)));
            case WRONG_CODE -> renderer.render(QqMsg.WRONG_CODE);
            case CODE_USED -> renderer.render(QqMsg.CODE_USED);
            case QQ_FULL -> renderer.render(QqMsg.QQ_FULL, Map.of(
                    QqMsg.Field.COUNT, String.valueOf(count),
                    QqMsg.Field.MAX, String.valueOf(max)));
            case PLAYER_FULL -> renderer.render(QqMsg.PLAYER_FULL,
                    Map.of(QqMsg.Field.MAX, String.valueOf(binds.settings().maxPerPlayer)));
            case COOLDOWN -> renderer.render(QqMsg.COOLDOWN,
                    Map.of(QqMsg.Field.SECONDS, String.valueOf(r.retryAfterSeconds())));
            case QQ_BANNED -> renderer.render(QqMsg.QQ_BANNED, Map.of(QqMsg.Field.REASON,
                    BindService.reasonPart(binds.bannedReasonOfQq(msg.userId()))));
        };
        reply(msg, text);
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
            reply(msg, renderer.render(QqMsg.SELF_UNBIND_OK, Map.of(
                    QqMsg.Field.PLAYER, accountName,
                    QqMsg.Field.COUNT, String.valueOf(remaining))));
            log.info("[unbind-self] qq=" + msg.userId() + " player=" + accountName
                    + " remaining=" + remaining);
        } else {
            reply(msg, renderer.render(QqMsg.SELF_UNBIND_NOTFOUND,
                    Map.of(QqMsg.Field.PLAYER, accountName)));
        }
    }

    /** 玩家查询：列出自己的绑定（与「解绑」无参列表一致）。 */
    private void handleQuery(OneBotEndpoint.IncomingMessage msg) {
        listMine(msg);
    }

    /** 我的绑定列表：头 + 条目×N（{index} 从 1 起）+ 可选解绑提示，整体去尾空白。 */
    private void listMine(OneBotEndpoint.IncomingMessage msg) {
        List<BindStore.Binding> mine = binds.findByQq(msg.userId());
        if (mine.isEmpty()) {
            reply(msg, renderer.render(QqMsg.NOT_BOUND));
            return;
        }
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.SELF_UNBIND_LIST_HEADER, Map.of(
                QqMsg.Field.COUNT, String.valueOf(mine.size()),
                QqMsg.Field.MAX, String.valueOf(binds.settings().maxPerQq))));
        int i = 1;
        for (BindStore.Binding b : mine) {
            sb.append('\n').append(renderer.render(QqMsg.SELF_UNBIND_LIST_ITEM, Map.of(
                    QqMsg.Field.INDEX, String.valueOf(i++),
                    QqMsg.Field.PLAYER, b.name(),
                    QqMsg.Field.TIME, fmtListTime(b.boundAt()))));
        }
        if (binds.settings().selfUnbind) {
            sb.append('\n').append(renderer.render(QqMsg.SELF_UNBIND_LIST_HINT));
        }
        reply(msg, sb.toString().stripTrailing());
    }

    // ================= 通用 =================

    /**
     * 回执：群 → 引用原消息 + @发送者 + 正文；私聊 → 纯文本。
     *
     * <p>全局字段注入：{@code {at}}（群 = at 码 + 换行，私聊 = 空串）与
     * {@code {sender}}（发送者 QQ）统一在此注入，渲染器不碰它们。
     * {@code {qq}} 只表示业务目标 QQ，由各调用点在渲染前替换完毕；本方法
     * 刻意不再兜底替换它——旧兜底会把漏替换 {qq} 的模板显示成发送者 QQ，
     * 掩盖配置/调用错误，现在漏替换会原样露出占位符以便暴露问题。
     *
     * <p>CQ 注入防护：正文里用户可控内容（玩家名/指令参数）可能携带 [CQ:...] 字样，
     * NapCat 会将其解析为多媒体卡片——可被用于借机器人名义发钓鱼内容。
     * 发送前破坏正文中的 CQ 码头（[CQ → [·CQ），插件自己拼的
     * reply/at 码在本方法内构造、不经过 sanitize，不受影响。
     */
    private void reply(OneBotEndpoint.IncomingMessage msg, String text) {
        // 先净化用户可控内容（含 {at} 占位符的原文本），再注入我们自己构造的 CQ 码
        String safe = sanitizeCq(text);
        String at = msg.isGroup() ? "[CQ:at,qq=" + msg.userId() + "]\n" : "";
        String body = safe.replace("{at}", at).replace("{sender}", String.valueOf(msg.userId()));
        body = body.replace("\\n", "\n");
        if (msg.isGroup()) {
            StringBuilder out = new StringBuilder();
            if (msg.messageId() != 0) {
                out.append("[CQ:reply,id=").append(msg.messageId()).append(']');
            }
            endpoint.sendGroupMessage(msg.groupId(), out.append(body).toString());
        } else {
            endpoint.sendPrivateMessage(msg.userId(), body.stripLeading());
        }
    }

    /** 破坏正文中的 CQ 码头：[CQ → [·CQ（显示几乎无差，解析必失败）。 */
    private static String sanitizeCq(String s) {
        return s.replace("[CQ", "[·CQ");
    }

    private static String name(BindService.BindResult r) {
        return r.created() == null ? "?" : r.created().name();
    }

    /** 目标展示标签：QQ 方向「QQ 12345」，玩家方向「玩家 Steve」。 */
    private static String targetLabel(AdminOps.Target target) {
        return switch (target) {
            case AdminOps.QqTarget t -> "QQ " + t.qq().digits();
            case AdminOps.NameTarget n -> "玩家 " + n.name();
        };
    }

    /** 拉黑原因片段：有原因 →「（原因: xxx）」，无 → 空串（模板里不留残括号）。 */
    private static String reasonNote(String reason) {
        return reason == null || reason.isBlank() ? "" : "（原因: " + reason.strip() + "）";
    }

    /** 列表时间戳 → 展示串：LIST 预设，格式与时区由配置决定。 */
    private String fmtListTime(long epochMilli) {
        return TimeFmt.format(TimeFmt.Preset.LIST, epochMilli, config, log::warning);
    }

    /** 存储读出的时间戳字符串可能被手改坏，formatSafe 兜底「时间未知」不抛异常。 */
    private String fmtListTimeSafe(String raw) {
        return TimeFmt.formatSafe(TimeFmt.Preset.LIST, raw, config, log::warning);
    }

    /** 玩家帮助：解绑行按 self-unbind 开关动态取舍（静态文案会误导）。 */
    private String playerHelp() {
        StringBuilder sb = new StringBuilder(renderer.render(QqMsg.HELP));
        if (binds.settings().selfUnbind) {
            sb.append("\n解绑 <账号名> —— 解绑指定账号");
        } else {
            // 关闭时不展示解绑行（发了也是静默忽略，展示即误导）
            sb.append("\n（自助解绑未开启，换绑请联系管理员）");
        }
        return sb.toString();
    }
}
