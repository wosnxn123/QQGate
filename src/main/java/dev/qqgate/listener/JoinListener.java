package dev.qqgate.listener;

import dev.qqgate.QQGatePlugin;
import dev.qqgate.bind.BindService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 进服拦截：PlayerLoginEvent（连接验证后、实体生成前）。
 *
 * 与 AuthMe 同一拦截层。客户端已进入配置阶段，disallow 断开包必然渲染。
 * 顺序保证：@LOWEST 先于 AuthMe 裁决。Folia：全局区域线程，无区域依赖。
 */
public final class JoinListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final QQGatePlugin plugin;
    private final BindService binds;
    /** 启动时读取并缓存（登录线程高并发下避免每次读配置的竞态；默认 false=不豁免）。包级可见供 diag 展示。 */
    volatile boolean opSkipBind = false;

    public JoinListener(QQGatePlugin plugin, BindService binds) {
        this.plugin = plugin;
        this.binds = binds;
        this.opSkipBind = plugin.configBool("kick.op-skip-bind-check", false);
    }
    /** /qqgateadmin reload 后刷新缓存。 */
    public void refreshConfig() {
        this.opSkipBind = plugin.configBool("kick.op-skip-bind-check", false);
    }

    public boolean isOpSkipBind() {
        return opSkipBind;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        // ① QQ 黑名单：OP 不豁免（管理员账号涉案时黑名单仍生效）
        if (binds.hasBannedQq(uuid)) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, MM.deserialize(renderBanKick(
                    "kick.banned-message",
                    "<red><b>该账号已被封禁</b></red>\n<gray>原因：账号绑定的QQ已被服务器拉黑\n如有异议请联系管理员申诉</gray>",
                    binds.bannedReasonForUuid(uuid))));
            return;
        }
        // ② 名字封禁：OP 同样不豁免
        if (binds.isNameBanned(player.getName())) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, MM.deserialize(renderBanKick(
                    "kick.name-banned-message",
                    "<red><b>该名称已被封禁</b></red>\n<gray>原因：此名称的历史账号曾绑定被拉黑的QQ\n如你是新玩家且首次使用此名称，请联系管理员处理</gray>",
                    binds.bannedReasonForName(player.getName()))));
            return;
        }
        if (player.isOp() && opSkipBind) {
            return;
        }
        // ④ 已绑定 → 放行，交给 AuthMe
        if (binds.isBound(uuid)) {
            return;
        }

        var code = binds.ensureCode(uuid, player.getName(), System.currentTimeMillis());
        Component msg = MM.deserialize(renderKickMessage(player.getName(), code));
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, msg);
    }

    /** 封禁踢出页渲染：{reason} → "（原因）"；无原因 → 空串（模板不留残括号）。 */
    private String renderBanKick(String path, String def, String reason) {
        return plugin.configString(path, def)
                .replace("{reason}", BindService.reasonPart(reason));
    }

    /**
     * 踢出页渲染：占位符替换 + 有效期 + 群引导段。
     * 群引导策略（{group_line}）：
     *   allow-all 开  → 「请加入机器人所在的任意QQ群」（不列群号）
     *   白名单模式   → 列出全部白名单群；有推荐群则置顶标 ★
     * {group} 保留为单一群号（推荐群 > 白名单第一个），兼容旧模板。
     */
    private String renderKickMessage(String playerName, BindService.PendingCode code) {
        String template = plugin.configString("kick.message", defaultTemplate());
        String groupLine = buildGroupLine();
        String group = primaryGroup();

        long expireMinutes = Math.max(1, plugin.configInt("bind.expire-minutes", 5));
        String expireTime = formatExpire(code.expiresAt());

        String relative = expireMinutes + " 分钟";
        String line = switch (plugin.configString("bind.expire-display", "both")) {
            case "relative" -> relative;
            case "absolute" -> expireTime;
            default -> relative + "（" + expireTime + " 前有效，过期请重连刷新）";
        };
        template = template.replace("{expire_line}", line);
        // 私聊渠道提示：开启时追加一行（allow-all 或白名单模式通用）
        String hint = privateHint();
        if (!hint.isEmpty()) {
            template = template.replace("{group_line}", groupLine + "\n" + hint);
        } else {
            template = template.replace("{group_line}", groupLine);
        }

        return template
                .replace("{code}", code.code())
                .replace("{group}", group)
                .replace("{player}", playerName)
                .replace("{expire_minutes}", String.valueOf(expireMinutes))
                .replace("{expire_time}", expireTime);
    }

    /**
     * 群引导内容（{group_line}）——只输出群号数据，引导语由模板负责：
     *   allow-all 开 + 有推荐群 → 推荐群 ★（或任意群）
     *   allow-all 开 + 无推荐群 → 通用提示语（无具体群号时自带完整句子）
     *   白名单模式            → 全部白名单群号（/ 分隔）
     *   白名单模式 + 白名单空  → 配置错误警告
     */
    private String buildGroupLine() {
        boolean allowAll = plugin.configBool("groups.allow-all", false);
        String recommended = plugin.configString("groups.recommended", "").trim();

        if (allowAll) {
            if (!recommended.isEmpty()) {
                return "<aqua>" + recommended + "</aqua> <yellow>★推荐</yellow>"
                        + "<gray>（或机器人所在的任意群）</gray>";
            }
            return "机器人所在的任意QQ群";
        }

        List<String> allowed = plugin.getConfig().getStringList("groups.allowed");
        Set<String> ordered = new LinkedHashSet<>();
        for (String g : allowed) {
            if (!g.trim().isEmpty()) ordered.add(g.trim());
        }
        if (ordered.isEmpty()) {
            return "<red><b>⚠ 未配置绑定群（config.yml groups）</b></red>";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String g : ordered) {
            if (i++ > 0) sb.append(" / ");
            sb.append("<aqua>").append(g).append("</aqua>");
        }
        return sb.toString();
    }
    private String primaryGroup() {
        String recommended = plugin.configString("groups.recommended", "").trim();
        if (!recommended.isEmpty()) return recommended;
        List<String> allowed = plugin.getConfig().getStringList("groups.allowed");
        if (!allowed.isEmpty()) return allowed.get(0).trim();
        return "机器人所在群";
    }

    /** 私聊渠道提示行（开启私聊绑定时追加在群引导后）。 */
    private String privateHint() {
        return plugin.configBool("private.allow-bind", false)
                ? "<gray>也可以私聊机器人发送上述指令完成绑定</gray>" : "";
    }

    private static String defaultTemplate() {
        return "<gold><b>QQGate</b></gold> <dark_gray>|</dark_gray> <red>请加入QQ群绑定后进入服务器</red>"
                + "\n\n<yellow>➤ 服务器群：<white>{group_line}</white></yellow>"
                + "\n<yellow>➤ 在群内发送：<green><b>绑定 {code}</b></green></yellow>"
                + "\n\n<aqua>➤ 绑定成功后重新连接即可进入</aqua>"
                + "\n<gray>【!】验证码 <white>{expire_minutes}</white> 分钟内有效（{expire_time} 前）"
                + "\n【!】过期后重新进服即可刷新</gray>";
    }

    private String formatExpire(long epochMilli) {
        String pattern = plugin.configString("bind.time-format", "HH:mm");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
        String tz = plugin.configString("bind.time-zone", "default");
        ZoneId zone = "default".equals(tz) ? ZoneId.systemDefault() : ZoneId.of(tz);
        return fmt.format(Instant.ofEpochMilli(epochMilli).atZone(zone));
    }
}
