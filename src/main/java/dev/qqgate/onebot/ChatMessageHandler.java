package dev.qqgate.onebot;

import dev.qqgate.BotConfig;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * 聊天指令处理（群 + 私聊）：解析「绑定 <码>」（带/不带空格、全角空格均可）与「解绑」。
 * 群：须在 groups.allowed 白名单内；私聊：须 private.allow-bind 开启，回执走私聊。
 * 仅依赖 BotConfig + Logger，可独立单测。
 */
public final class ChatMessageHandler implements OneBotEndpoint.MessageListener {

    /** 「绑定」+ 4~8 位数字，分隔符允许空格/全角空格/冒号，或紧贴。 */
    static final Pattern BIND = Pattern.compile(
            "^[\\s\\u3000]*绑定[\\s\\u3000:：]*(\\d{4,8})[\\s\\u3000]*$");
    static final Pattern UNBIND = Pattern.compile("^[\\s\\u3000]*解绑[\\s\\u3000]*$");
    /** 剥离 CQ 码。 */
    static final Pattern CQ = Pattern.compile("\\[CQ:[^\\]]*]");

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
        } else if (!config.configBool("private.allow-bind", false)) {
            return; // 私聊绑定未开启：静默忽略
        }

        String text = CQ.matcher(msg.rawMessage()).replaceAll("").trim();
        long now = System.currentTimeMillis();

        Matcher m = BIND.matcher(text);
        if (m.matches()) {
            handleBind(msg, m.group(1), now);
            return;
        }
        if (UNBIND.matcher(text).matches()) {
            handleUnbind(msg);
        }
    }

    private boolean groupAllowed(long groupId) {
        if (config.configBool("groups.allow-all", false)) {
            return true; // 开关：任何群都可执行绑定等操作
        }
        for (String g : config.configStringList("groups.allowed")) {
            if (g.trim().equals(String.valueOf(groupId))) {
                return true;
            }
        }
        return false;
    }

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

    private void handleUnbind(OneBotEndpoint.IncomingMessage msg) {
        if (!binds.settings().selfUnbind) {
            return; // 未开启自助解绑：静默忽略
        }
        List<BindStore.Binding> mine = binds.findByQq(msg.userId());
        if (mine.isEmpty()) {
            reply(msg, msg("messages.not-bound", "{at} 你当前没有绑定任何账号"));
            return;
        }
        BindStore.Binding b = mine.get(0);
        boolean ok = binds.selfUnbind(msg.userId(), b.uuid());
        int remaining = ok ? mine.size() - 1 : mine.size();
        reply(msg, msg("messages.self-unbind-ok", "{at} 已解绑账号 {player}（该QQ还剩 {count} 个绑定）")
                .replace("{player}", b.name())
                .replace("{count}", String.valueOf(remaining)));
        if (ok) {
            log.info("[unbind-self] qq=" + msg.userId() + " player=" + b.name()
                    + " remaining=" + remaining);
        }
    }

    /** 回执：群 → 引用原消息 + @发送者 + 换行 + 正文；私聊 → 纯文本私聊。 */
    private void reply(OneBotEndpoint.IncomingMessage msg, String text) {
        if (msg.isGroup()) {
            StringBuilder out = new StringBuilder();
            if (msg.messageId() != 0) {
                out.append("[CQ:reply,id=").append(msg.messageId()).append(']');
            }
            // @ 后强制换行：CQ 码紧贴文字时 QQ 客户端不自动分行
            String at = "[CQ:at,qq=" + msg.userId() + "]\n";
            String body = text.replace("{at}", at).replace("{qq}", String.valueOf(msg.userId()));
            // 文本模板里的 \n 字面量 → 真实换行
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
}
