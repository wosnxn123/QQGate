package dev.qqgate;

import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindSettings;
import dev.qqgate.bind.BindStore;
import dev.qqgate.command.QQGateAdminCommand;
import dev.qqgate.command.QQGateCommand;
import dev.qqgate.listener.JoinListener;
import dev.qqgate.onebot.ChatMessageHandler;
import dev.qqgate.onebot.OneBotEndpoint;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Locale;

/**
 * QQGate 插件入口。装配配置、存储、绑定服务、OneBot 端点、监听器与命令。
 *
 * Folia 线程纪律：WS 线程绝不直接调用 Bukkit 世界 API；
 * 游戏侧操作一律经 GlobalRegionScheduler。
 */
public final class QQGatePlugin extends JavaPlugin implements BotConfig {

    private BindStore store;
    private BindService bindService;
    private OneBotEndpoint endpoint;
    private JoinListener joinListener;

    @Override
    public void onEnable() {
        // 配置自动升级：旧配置补齐新段（只增不改，写前备份）
        try {
            var result = dev.qqgate.config.ConfigUpgrader.upgradeIfNeeded(
                    getDataFolder().toPath().resolve("config.yml"),
                    new String(getResource("config.yml").readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
            if (result.upgraded()) {
                getLogger().info("配置已自动升级 v" + result.fromVersion() + " -> v" + result.toVersion()
                        + "（新增 " + result.addedKeys() + " 项，备份: " + result.backup() + "）");
            }
        } catch (Exception e) {
            getLogger().warning("配置升级检查失败（继续用现有配置）: " + e);
        }
        saveDefaultConfig();
        Path dataFolder = getDataFolder().toPath();

        this.store = new BindStore(dataFolder, () -> configBool("storage.pretty-print", true));
        this.store.load();

        this.bindService = new BindService(store);
        this.bindService.updateSettings(buildSettings());

        this.endpoint = new OneBotEndpoint(this, getLogger());
        ChatMessageHandler chatHandler = new ChatMessageHandler(this, bindService, endpoint, getLogger());
        endpoint.setMessageHandler(chatHandler);
        this.joinListener = new JoinListener(this, bindService);
        getServer().getPluginManager().registerEvents(joinListener, this);

        endpoint.start();


        PluginCommand cmd = getCommand("qqgate");
        if (cmd != null) {
            QQGateCommand playerCmd = new QQGateCommand(this, bindService);
            cmd.setExecutor(playerCmd);
            cmd.setTabCompleter(playerCmd);
        }
        PluginCommand adminCmd = getCommand("qqgateadmin");
        if (adminCmd != null) {
            QQGateAdminCommand executor = new QQGateAdminCommand(this, bindService, endpoint);
            adminCmd.setExecutor(executor);
        }
        getServer().getAsyncScheduler().runAtFixedRate(this, t -> bindService.purgeExpired(),
                30_000L, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS);

        getLogger().info("QQGate enabled. "
                + String.join(" | ", dev.qqgate.config.ConfigUpgrader.summaryOf(getConfig())));
    }

    @Override
    public void onDisable() {
        if (endpoint != null) endpoint.stop();
        if (store != null) store.save();
    }

    private BindSettings buildSettings() {
        return new BindSettings(
                configInt("bind.code-length", 4),
                configInt("bind.expire-minutes", 5) * 60_000L,
                parseEnum("bind.expire-display", BindSettings.ExpireDisplay.BOTH),
                configInt("bind.max-per-qq", 1),
                configInt("bind.max-per-player", 1),
                parseEnum("bind.limit-policy", BindSettings.LimitPolicy.REJECT),
                configBool("bind.self-unbind", false),
                configInt("bind.cooldown-seconds", 10),
                configBool("bind.refresh-on-rejoin", true));
    }

    private <E extends Enum<E>> E parseEnum(String path, E def) {
        String raw = configString(path, def.name());
        try {
            return Enum.valueOf(def.getDeclaringClass(), raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid value at " + path + ": " + raw + ", using " + def);
            return def;
        }
    }

    // ---- 配置读取窄接口 ----

    public String configString(String path, String def) {
        return getConfig().getString(path, def);
    }

    public int configInt(String path, int def) {
        return getConfig().getInt(path, def);
    }

    public boolean configBool(String path, boolean def) {
        return getConfig().getBoolean(path, def);
    }

    @Override
    public java.util.List<String> configStringList(String path) {
        return getConfig().getStringList(path);
    }

    public BindService bindService() {
        return bindService;
    }

    public OneBotEndpoint endpoint() {
        return endpoint;
    }

    public BindStore store() {
        return store;
    }

    /** 登录拦截器的 OP 缓存值（diag 展示运行时真实读取值）。 */
    public boolean joinListenerSkip() {
        return joinListener != null && joinListener.isOpSkipBind();
    }

    public void reloadAll() {
        reloadConfig();
        bindService.updateSettings(buildSettings());
        if (joinListener != null) joinListener.refreshConfig();
    }
}
