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

    public JoinListener(QQGatePlugin plugin, BindService binds) {
        this.plugin = plugin;
        this.binds = binds;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        var uuid = event.getPlayer().getUniqueId();
        if (binds.isBound(uuid)) {
            return; // 已绑定 → 放行，交给 AuthMe
        }
        if (event.getPlayer().isOp()) {
            return; // bypass：登录阶段权限插件未加载，仅 OP 判断
        }

        var code = binds.ensureCode(uuid, event.getPlayer().getName(), System.currentTimeMillis());
        Component msg = MM.deserialize(renderKickMessage(event.getPlayer().getName(), code));
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, msg);
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
     * 群引导段（{group_line}）：
     *   allow-all 开 + 有推荐群 → 显示推荐群（官方入口）+「或任意群」补充
     *   allow-all 开 + 无推荐群 → 「请加入机器人所在的任意QQ群」
     *   白名单模式            → 列出 allowed 全部群（推荐群不掺入）
     *   白名单模式 + 白名单空  → 配置错误警告（不兜底：显示层须与裁决层一致，
     *                           白名单空的群发码本就不会响应，引导过去=误导）
     */
    private String buildGroupLine() {
        boolean allowAll = plugin.configBool("groups.allow-all", false);
        String recommended = plugin.configString("groups.recommended", "").trim();

        if (allowAll) {
            if (!recommended.isEmpty()) {
                return "请加群：<aqua>" + recommended + "</aqua> <yellow>★推荐</yellow>"
                        + "<gray>（或机器人所在的任意群）</gray>";
            }
            return "请加入机器人所在的任意QQ群";
        }

        List<String> allowed = plugin.getConfig().getStringList("groups.allowed");
        Set<String> ordered = new LinkedHashSet<>();
        for (String g : allowed) {
            if (!g.trim().isEmpty()) ordered.add(g.trim());
        }
        if (ordered.isEmpty()) {
            return "<red><b>⚠ 服务器未配置绑定群，请联系服主检查 config.yml（groups）</b></red>";
        }
        StringBuilder sb = new StringBuilder("请加群：");
        int i = 0;
        for (String g : ordered) {
            if (i++ > 0) sb.append(" / ");
            sb.append("<aqua>").append(g).append("</aqua>");
        }
        return sb.toString();
    }

    /** 单一群号占位符（{group}）：推荐群 > 白名单第一个 > 兜底文本。 */
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
        return "<gold><b>QQGate</b></gold> <dark_gray>»</dark_gray> <red>你还未绑定QQ，暂时无法进入服务器</red>"
                + "\n\n<white>{group_line}</white>"
                + "\n<white>然后在群内发送：</white>"
                + "\n\n        <green><b>绑定 {code}</b></green>"
                + "\n\n<yellow>验证码有效期 <white>{expire_minutes}</white> 分钟"
                + "（<white>{expire_time}</white> 前有效）"
                + "\n过期请重新连接服务器刷新验证码</yellow>"
                + "\n<gray>绑定成功后重新连接即可进入服务器</gray>";
    }

    private String formatExpire(long epochMilli) {
        String pattern = plugin.configString("bind.time-format", "HH:mm");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
        String tz = plugin.configString("bind.time-zone", "default");
        ZoneId zone = "default".equals(tz) ? ZoneId.systemDefault() : ZoneId.of(tz);
        return fmt.format(Instant.ofEpochMilli(epochMilli).atZone(zone));
    }
}
