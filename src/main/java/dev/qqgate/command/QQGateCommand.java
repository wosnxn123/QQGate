package dev.qqgate.command;

import dev.qqgate.QQGatePlugin;
import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /qqgate —— 玩家命令：
 *   help   帮助
 *   bind   给自己生成验证码（游戏内聊天栏显示，含有效期）
 *   info   查自己的绑定
 *
 * bind 生成码与踢出页同源（BindService.ensureCode），信道为本人客户端聊天栏。
 */
public final class QQGateCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final QQGatePlugin plugin;
    private final BindService binds;

    public QQGateCommand(QQGatePlugin plugin, BindService binds) {
        this.plugin = plugin;
        this.binds = binds;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令仅玩家可用（管理命令见 /qqgateadmin）");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "bind" -> handleBind(player);
            case "info" -> handleInfo(player);
            default -> sendHelp(player);
        }
        return true;
    }

    /** 游戏内拿码：与踢出页同一渲染（码+有效期）。 */
    private void handleBind(Player player) {
        UUID uuid = player.getUniqueId();
        // 冷却：防刷码（同一玩家 5 秒一次）
        if (!player.hasMetadata("qqgate.bindcd")) {
            player.setMetadata("qqgate.bindcd",
                    new org.bukkit.metadata.FixedMetadataValue(plugin, System.currentTimeMillis()));
        } else {
            long last = player.getMetadata("qqgate.bindcd").get(0).asLong();
            if (System.currentTimeMillis() - last < 5_000L) {
                player.sendMessage("§e[QQGate] 操作太频繁，请稍后再试");
                return;
            }
            player.setMetadata("qqgate.bindcd",
                    new org.bukkit.metadata.FixedMetadataValue(plugin, System.currentTimeMillis()));
        }

        // 已满额：拒绝发码（发了也无用，且诱导玩家重复群消息）
        int mine = binds.findByUuid(uuid).size();
        if (mine >= binds.settings().maxPerPlayer) {
            player.sendMessage("§6[QQGate] §f你已绑定QQ（" + mine + "/"
                    + binds.settings().maxPerPlayer + "），无需重复绑定。");
            player.sendMessage("§7换绑请联系管理员，或等待 limit-policy 自动替换。");
            return;
        }

        BindService.PendingCode code = binds.ensureCode(uuid, player.getName(),
                System.currentTimeMillis());
        String template = plugin.configString("bind.code-message",
                "<gold><b>QQGate</b></gold> <dark_gray>»</dark_gray> <yellow>你的验证码：<green><b>绑定 {code}</b></green>\n"
                        + "<gray>发送到群 <aqua>{group}</aqua> 完成绑定\n"
                        + "有效期 {expire_minutes} 分钟（{expire_time} 前有效）</gray>");
        String rendered = renderCodeMessage(player.getName(), code, template);
        player.sendMessage(MM.deserialize(rendered));
    }

    private void handleInfo(Player player) {
        List<BindStore.Binding> mine = binds.findByUuid(player.getUniqueId());
        if (mine.isEmpty()) {
            player.sendMessage("§7[QQGate] 你尚未绑定QQ。进入服务器时会被要求绑定，"
                    + "或使用 §f/qqgate bind §7主动获取验证码。");
            return;
        }
        player.sendMessage("§6[QQGate] §f你的绑定（" + mine.size() + " 条）：");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (BindStore.Binding b : mine) {
            player.sendMessage("§7  QQ §f" + maskQq(b.qq()) + " §8· "
                    + fmt.format(Instant.ofEpochMilli(b.boundAt()).atZone(ZoneId.systemDefault())));
        }
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
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(
                plugin.configString("bind.time-format", "HH:mm"));
        String tz = plugin.configString("bind.time-zone", "default");
        ZoneId zone = "default".equals(tz) ? ZoneId.systemDefault() : ZoneId.of(tz);
        return fmt.format(Instant.ofEpochMilli(epochMilli).atZone(zone));
    }

    private void sendHelp(Player player) {
        player.sendMessage("""
                §6[QQGate] 玩家命令：
                §f  /qqgate bind    §7获取绑定验证码（在QQ群内发送「绑定 <码>」完成绑定）
                §f  /qqgate info    §7查看自己的绑定状态
                §f  /qqgate help    §7显示本帮助
                §7管理员命令：§f/qqgateadmin""");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("bind", "info", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
