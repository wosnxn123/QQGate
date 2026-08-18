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
import java.util.List;

/**
 * 进服拦截：PlayerLoginEvent（连接验证后、实体生成前）。
 *
 * 与 AuthMe 同一拦截层（其 Paper 路径为 PlayerConnectionValidateLoginEvent，
 * 旧路径为 PlayerLoginEvent）。此时客户端已完成登录握手进入配置阶段，
 * disallow 断开包必然被渲染为踢出屏，无"连接中断"竞态，delay-ms 归 0。
 *
 * 顺序保证：@LOWEST 先于 AuthMe 的监听器裁决。
 * Folia：本事件在全局区域线程触发，无区域依赖。
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
            return; // 已被更早的规则拒绝（封禁/满员等），不覆盖
        }
        var uuid = event.getPlayer().getUniqueId();
        if (binds.isBound(uuid)) {
            return; // 已绑定 → 放行，交给 AuthMe
        }
        // bypass：仅 OP 可跳过（登录阶段权限插件数据未加载）
        if (event.getPlayer().isOp()) {
            return;
        }

        var code = binds.ensureCode(uuid, event.getPlayer().getName(), System.currentTimeMillis());
        Component msg = MM.deserialize(renderKickMessage(event.getPlayer().getName(), code));
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, msg);
    }

    /** 渲染踢出页文案：占位符替换 + 有效期显示模式。 */
    private String renderKickMessage(String playerName, BindService.PendingCode code) {
        String template = plugin.configString("kick.message",
                "<red>未绑定QQ，请在群内发送：绑定 {code}</red>");
        String group = firstAllowedGroup();

        long expireMinutes = Math.max(1, plugin.configInt("bind.expire-minutes", 5));
        String expireTime = formatExpire(code.expiresAt());

        String relative = expireMinutes + " 分钟";
        String line = switch (plugin.configString("bind.expire-display", "both")) {
            case "relative" -> relative;
            case "absolute" -> expireTime;
            default -> relative + "（" + expireTime + " 前有效，过期请重连刷新）";
        };
        // 只替换模板中出现的有效期组合占位符
        template = template.replace("{expire_line}", line);

        return template
                .replace("{code}", code.code())
                .replace("{group}", group)
                .replace("{player}", playerName)
                .replace("{expire_minutes}", String.valueOf(expireMinutes))
                .replace("{expire_time}", expireTime);
    }

    private String firstAllowedGroup() {
        List<String> groups = plugin.getConfig().getStringList("groups.allowed");
        return groups.isEmpty() ? "（未配置群号，请检查 config.yml）" : groups.get(0);
    }

    private String formatExpire(long epochMilli) {
        String pattern = plugin.configString("bind.time-format", "HH:mm");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
        String tz = plugin.configString("bind.time-zone", "default");
        ZoneId zone = "default".equals(tz) ? ZoneId.systemDefault() : ZoneId.of(tz);
        return fmt.format(Instant.ofEpochMilli(epochMilli).atZone(zone));
    }
}
