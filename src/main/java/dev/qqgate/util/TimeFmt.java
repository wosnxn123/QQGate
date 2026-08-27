package dev.qqgate.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 配置驱动的时间格式化：坏配置降级，而不是抛异常。
 * <p>{@code bind.time-format} 写错模式串或 {@code bind.time-zone} 写错时区名时，
 * {@code DateTimeFormatter.ofPattern} / {@code ZoneId.of} 会抛
 * {@code IllegalArgumentException} / {@code DateTimeException}，
 * 从而打断发码与踢出页渲染（玩家看到的是「内部错误」而不是验证码）。
 * 这里统一兜底，并对同一个坏值只告警一次，避免每次进服刷日志。
 */
public final class TimeFmt {

    private TimeFmt() {
    }

    /** 兜底模式串。 */
    public static final String FALLBACK_PATTERN = "HH:mm";

    private static final DateTimeFormatter FALLBACK = DateTimeFormatter.ofPattern(FALLBACK_PATTERN);

    /** 已告警过的坏配置值。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 按配置格式化时刻；模式或时区非法时降级并告警一次。 */
    public static String format(long epochMilli, String pattern, String zone, Consumer<String> warn) {
        return formatter(pattern, warn)
                .format(Instant.ofEpochMilli(epochMilli).atZone(zone(zone, warn)));
    }

    /** 解析模式串，非法则回退 {@link #FALLBACK_PATTERN}。 */
    public static DateTimeFormatter formatter(String pattern, Consumer<String> warn) {
        if (pattern == null || pattern.isBlank()) {
            return FALLBACK;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (RuntimeException e) {
            warnOnce("pattern:" + pattern, warn,
                    "bind.time-format 无效（" + pattern + "）: " + e.getMessage()
                            + "，已回退 " + FALLBACK_PATTERN);
            return FALLBACK;
        }
    }

    /** 解析时区，"default"/空/非法一律回退服务器时区。 */
    public static ZoneId zone(String tz, Consumer<String> warn) {
        if (tz == null || tz.isBlank() || "default".equalsIgnoreCase(tz)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            warnOnce("zone:" + tz, warn,
                    "bind.time-zone 无效（" + tz + "）: 已回退服务器时区 " + ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }

    private static void warnOnce(String key, Consumer<String> warn, String message) {
        if (warn != null && WARNED.add(key)) {
            warn.accept(message);
        }
    }

    /** 测试用：清空告警去重表。 */
    public static void resetWarnings() {
        WARNED.clear();
    }
}
