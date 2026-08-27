package dev.qqgate.command;

import dev.qqgate.QQGatePlugin;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindSettings;
import dev.qqgate.bind.BindStore;
import dev.qqgate.util.TimeFmt;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * /qqgate —— 玩家命令：bind 取码、info 查绑定、help 帮助。
 * <p>子指令表由 {@link PlayerSub} 驱动（派发 / 帮助 / Tab 补全同源），
 * 输出样式统一走 {@link Msg}；bind 生成码与踢出页同源（{@link BindService#ensureCode}），
 * 信道为本人客户端聊天栏。
 */
public final class QQGateCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 游戏内取码冷却：防止刷屏刷码（与 bind.cooldown-seconds 无关，后者按 QQ 计）。 */
    private static final long BIND_COOLDOWN_MILLIS = 5_000L;

    private static final String CD_KEY = "qqgate.bindcd";

    private final QQGatePlugin plugin;
    private final BindService binds;

    public QQGateCommand(QQGatePlugin plugin, BindService binds) {
        this.plugin = plugin;
        this.binds = binds;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.err("该指令仅玩家可用。"));
            sender.sendMessage(Msg.hint("控制台请用 §f/qqgateadmin§7（status / lookup / bind …）"));
            return true;
        }
        Optional<PlayerSub> parsed = PlayerSub.of(args.length == 0 ? null : args[0]);
        if (parsed.isEmpty()) {
            player.sendMessage(Msg.err("未知子指令 §f" + args[0]));
            sendHelp(player);
            return true;
        }
        switch (parsed.get()) {
            case BIND -> handleBind(player);
            case INFO -> handleInfo(player);
            case HELP -> sendHelp(player);
        }
        return true;
    }

    /** 游戏内拿码：与踢出页同一渲染（码 + 有效期）。 */
    private void handleBind(Player player) {
        long now = System.currentTimeMillis();
        long remaining = cooldownRemaining(player, now);
        if (remaining > 0) {
            player.sendMessage(Msg.warn("操作太频繁，请 §e" + remaining + "§f 秒后再试"));
            return;
        }

        UUID uuid = player.getUniqueId();
        BindSettings settings = binds.settings();
        int mine = binds.findByUuid(uuid).size();
        if (mine >= settings.maxPerPlayer) {
            // REJECT 下发码无意义（验证必然被拒），只有 REPLACE 才能继续绑定并挤掉最早一条。
            if (settings.limitPolicy != BindSettings.LimitPolicy.REPLACE) {
                player.sendMessage(Msg.info("你已绑定 §f" + mine + "§7/§f" + settings.maxPerPlayer
                        + "§7 个QQ，无需重复绑定"));
                player.sendMessage(settings.selfUnbind
                        ? Msg.hint("换绑：先在QQ群发送 §f解绑 " + player.getName() + "§7，再重新取码")
                        : Msg.hint("换绑请联系管理员（服务器未开放自助解绑）"));
                return;
            }
            player.sendMessage(Msg.warn("你已绑定 §e" + mine + "§f/" + settings.maxPerPlayer
                    + " 个QQ，继续绑定会挤掉最早的一条"));
        }

        stampCooldown(player, now);
        BindService.PendingCode code = binds.ensureCode(uuid, player.getName(), now);
        String template = plugin.configString("bind.code-message",
                "<gold><b>QQGate</b></gold> <dark_gray>»</dark_gray> <yellow>你的验证码：<green><b>绑定 {code}</b></green>\n"
                        + "<gray>发送到群 <aqua>{group}</aqua> 完成绑定\n"
                        + "有效期 {expire_minutes} 分钟（{expire_time} 前有效）</gray>");
        player.sendMessage(MM.deserialize(renderCodeMessage(player.getName(), code, template)));
    }

    private void handleInfo(Player player) {
        List<BindStore.Binding> mine = binds.findByUuid(player.getUniqueId());
        BindSettings settings = binds.settings();
        if (mine.isEmpty()) {
            player.sendMessage(Msg.info("你尚未绑定QQ"));
            player.sendMessage(Msg.hint("用 §f/qqgate bind §7取码，再在QQ群发送 §f绑定 <验证码>"));
            return;
        }
        player.sendMessage(Msg.title("你的绑定 §e" + mine.size() + "§f/§e" + settings.maxPerPlayer));
        String zone = plugin.configString("bind.time-zone", "default");
        for (BindStore.Binding b : mine) {
            player.sendMessage(Msg.item("QQ " + maskQq(b.qq()),
                    "绑定于 " + TimeFmt.format(b.boundAt(), "yyyy-MM-dd HH:mm", zone,
                            plugin.getLogger()::warning)));
        }
        if (settings.selfUnbind) {
            player.sendMessage(Msg.footer("自助解绑：在QQ群发送 §f解绑 " + player.getName()));
        }
    }

    /** 剩余冷却秒数（向上取整），0 表示可用。 */
    private long cooldownRemaining(Player player, long now) {
        if (!player.hasMetadata(CD_KEY)) {
            return 0L;
        }
        long elapsed = now - player.getMetadata(CD_KEY).get(0).asLong();
        if (elapsed >= BIND_COOLDOWN_MILLIS || elapsed < 0) {
            return 0L;
        }
        return (BIND_COOLDOWN_MILLIS - elapsed + 999L) / 1000L;
    }

    private void stampCooldown(Player player, long now) {
        player.setMetadata(CD_KEY, new FixedMetadataValue(plugin, now));
    }

    /** QQ 打码显示（隐私）：保留前2后2。 */
    static String maskQq(long qq) {
        String s = String.valueOf(qq);
        if (s.length() <= 4) return s;
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }

    private String renderCodeMessage(String playerName, BindService.PendingCode code, String template) {
        String group = firstAllowedGroup();
        long expireMinutes = Math.max(1, plugin.configInt("bind.expire-minutes", 5));
        String expireTime = formatExpire(code.expiresAt());
        String line = switch (plugin.configString("bind.expire-display", "both")) {
            case "relative" -> expireMinutes + " 分钟";
            case "absolute" -> expireTime;
            default -> expireMinutes + " 分钟（" + expireTime + " 前有效）";
        };
        return template
                .replace("{expire_line}", line)
                .replace("{code}", code.code())
                .replace("{group}", group)
                .replace("{player}", playerName)
                .replace("{expire_minutes}", String.valueOf(expireMinutes))
                .replace("{expire_time}", expireTime)
                // 踢出页模板含"暂时无法进入"措辞，游戏内发送时替换为引导语
                .replace("你还未绑定QQ，暂时无法进入服务器", "你的验证码如下，发送到群里即可完成绑定");
    }

    private String firstAllowedGroup() {
        List<String> groups = plugin.getConfig().getStringList("groups.allowed");
        return groups.isEmpty() ? "（未配置群号）" : groups.get(0);
    }

    private String formatExpire(long epochMilli) {
        return TimeFmt.format(epochMilli,
                plugin.configString("bind.time-format", TimeFmt.FALLBACK_PATTERN),
                plugin.configString("bind.time-zone", "default"),
                plugin.getLogger()::warning);
    }

    private void sendHelp(Player player) {
        player.sendMessage(Msg.header("玩家指令"));
        for (PlayerSub s : PlayerSub.values()) {
            player.sendMessage(Msg.cmdRow("qqgate " + s.token(), s.args(), s.desc()));
        }
        player.sendMessage(Msg.footer("管理员指令见 §f/qqgateadmin help"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(PlayerSub.values().length);
            for (PlayerSub s : PlayerSub.values()) {
                if (s.token().startsWith(prefix)) {
                    out.add(s.token());
                }
            }
            return out;
        }
        return List.of();
    }
}
