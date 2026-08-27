package dev.qqgate.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.qqgate.BotConfig;

/**
 * 配置驱动的时间格式化：坏配置降级，而不是抛异常。
 * <p>调用方只走单入口 {@link #format(Preset, long, BotConfig, Consumer)}
 * 及其容错版 {@link #formatSafe(Preset, String, BotConfig, Consumer)}：
 * 由它们读配置、把键名传给底层原语 {@link #formatter}/{@link #zone}，
 * 保证告警文案永远点名真实的配置键。
 * <p>模式串或时区名写错时，{@code DateTimeFormatter.ofPattern} / {@code ZoneId.of}
 * 会抛 {@code IllegalArgumentException} / {@code DateTimeException}，
 * 从而打断发码与踢出页渲染（玩家看到的是「内部错误」而不是验证码）。
 * 这里统一兜底，并对同一个坏值只告警一次，避免每次进服刷日志。
 */
public final class TimeFmt {

    private TimeFmt() {
    }

    /** 兜底模式串，也是 {@link Preset#EXPIRE} 的默认值。 */
    public static final String FALLBACK_PATTERN = "HH:mm";

    /**
     * 时区配置键。全服一处、所有 preset 共用——这是刻意的：
     * 同一台服务器不该用两个不同的时区显示时间戳。
     */
    public static final String ZONE_KEY = "bind.time-zone";

    /** 时间戳损坏时 {@link #formatSafe} 的统一哨兵值。 */
    public static final String UNKNOWN_TIME = "时间未知";

    /** 表示「跟随服务器时区」的时区配置值。 */
    private static final String DEFAULT_ZONE = "default";

    /** 已告警过的坏配置值（去重键 = 种类 + 配置键 + 坏值）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * 模式串缓存。key 只可能来自配置（各 {@link Preset#patternKey()} 的取值），
     * 不是用户输入，运行时正常只有 2-3 个值，因此不引入淘汰逻辑。
     * {@code DateTimeFormatter} 不可变且线程安全，跨线程共享无虞。
     */
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    /** 时区缓存，上界同 {@link #FORMATTERS}。{@code ZoneId} 同样不可变且线程安全。 */
    private static final Map<String, ZoneId> ZONES = new ConcurrentHashMap<>();

    /**
     * 时间显示用途：每个常量绑定「配置键 + 默认模式串」。
     * <ul>
     *   <li>{@link #EXPIRE} —— 验证码过期时间（踢出页与 /qqgate bind）；</li>
     *   <li>{@link #LIST} —— 列表里的时间戳（QQ 侧与游戏内管理列表、玩家自己的绑定列表）。</li>
     * </ul>
     */
    public enum Preset {
        /** 验证码过期时间（踢出页与 /qqgate bind）。 */
        EXPIRE("bind.time-format", FALLBACK_PATTERN),
        /** 列表里的时间戳（QQ 侧与游戏内管理列表、玩家自己的绑定列表）。 */
        LIST("display.list-time-format", "yyyy-MM-dd HH:mm");

        private final String patternKey;
        private final String defaultPattern;

        Preset(String patternKey, String defaultPattern) {
            this.patternKey = patternKey;
            this.defaultPattern = defaultPattern;
        }

        /** 模式串的配置键。 */
        public String patternKey() {
            return patternKey;
        }

        /** 缺配置、配置为空或模式串非法时使用的模式串。 */
        public String defaultPattern() {
            return defaultPattern;
        }
    }

    /**
     * 单入口：从 {@code cfg} 读 {@code p.patternKey()}（缺省 {@code p.defaultPattern()}）
     * 与 {@link #ZONE_KEY}（缺省 {@code "default"}），走底层原语格式化。
     */
    public static String format(Preset p, long epochMilli, BotConfig cfg, Consumer<String> warn) {
        String pattern = cfg.configString(p.patternKey(), p.defaultPattern());
        String tz = cfg.configString(ZONE_KEY, DEFAULT_ZONE);
        return format(epochMilli, pattern, p.defaultPattern(), p.patternKey(), tz, ZONE_KEY, warn);
    }

    /**
     * 容忍存储文件里读出的原始时间戳字符串。
     * <p>存储文件里的时间戳可能被手改坏，一条脏数据不该炸掉整个列表
     * （{@code QQGateAdminCommand.fmtTimeSafe} 与 {@code ChatMessageHandler.epochOf}
     * 曾为此各维护一份同样的容错逻辑，现收口到这里）。
     * {@code null}/空/非数字/溢出（{@code Long.parseLong} 失败）一律返回
     * {@link #UNKNOWN_TIME}，不抛异常、不告警。
     */
    public static String formatSafe(Preset p, String rawEpochMilli, BotConfig cfg, Consumer<String> warn) {
        if (rawEpochMilli == null) {
            return UNKNOWN_TIME;
        }
        long epoch;
        try {
            epoch = Long.parseLong(rawEpochMilli);
        } catch (NumberFormatException e) {
            return UNKNOWN_TIME;
        }
        return format(p, epoch, cfg, warn);
    }

    /**
     * 底层原语的组合：按给定模式串与时区格式化；各自非法时降级并告警一次
     * （告警点名配置键）。常规调用请走
     * {@link #format(Preset, long, BotConfig, Consumer)}。
     *
     * @param fallbackPattern pattern 非法时的回退模式串，本身必须合法
     */
    public static String format(long epochMilli, String pattern, String fallbackPattern,
                                String patternKey, String zone, String zoneKey, Consumer<String> warn) {
        return formatter(pattern, fallbackPattern, patternKey, warn)
                .format(Instant.ofEpochMilli(epochMilli).atZone(zone(zone, zoneKey, warn)));
    }

    /**
     * 解析模式串（带缓存）；非法则回退 {@code fallbackPattern} 并告警一次，
     * 告警文案点名配置键 {@code key}。{@code null}/空白静默降级、不告警。
     *
     * @param fallbackPattern 本身必须合法（各 {@link Preset#defaultPattern()}
     *                        与 {@link #FALLBACK_PATTERN} 均为编译期常量）
     */
    public static DateTimeFormatter formatter(String pattern, String fallbackPattern,
                                              String key, Consumer<String> warn) {
        if (pattern == null || pattern.isBlank()) {
            return cachedFormatter(fallbackPattern);
        }
        try {
            return cachedFormatter(pattern);
        } catch (RuntimeException e) {
            warnOnce("pattern:" + key + ":" + pattern, warn,
                    key + " 无效（" + pattern + "）: " + e.getMessage()
                            + "，已回退 " + fallbackPattern);
            return cachedFormatter(fallbackPattern);
        }
    }

    /**
     * 解析时区（带缓存）；{@code "default"}/空白回退服务器时区；
     * 非法时同样回退服务器时区并告警一次，告警文案点名配置键 {@code key}。
     */
    public static ZoneId zone(String tz, String key, Consumer<String> warn) {
        if (tz == null || tz.isBlank() || DEFAULT_ZONE.equalsIgnoreCase(tz)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZONES.computeIfAbsent(tz, ZoneId::of);
        } catch (RuntimeException e) {
            warnOnce("zone:" + key + ":" + tz, warn,
                    key + " 无效（" + tz + "）: 已回退服务器时区 " + ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }

    private static DateTimeFormatter cachedFormatter(String pattern) {
        return FORMATTERS.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }

    private static void warnOnce(String dedupeKey, Consumer<String> warn, String message) {
        if (warn != null && WARNED.add(dedupeKey)) {
            warn.accept(message);
        }
    }

    /** 测试用：清空告警去重表。 */
    public static void resetWarnings() {
        WARNED.clear();
    }
}
