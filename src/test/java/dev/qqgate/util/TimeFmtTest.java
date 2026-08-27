package dev.qqgate.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.qqgate.BotConfig;
import dev.qqgate.util.TimeFmt.Preset;

/**
 * TimeFmt 契约测试：坏配置降级且告警点名真实配置键、同一坏值只告警一次；
 * 存储里的脏时间戳不抛异常。固定时点 + 固定时区做确定性断言。
 */
class TimeFmtTest {

    /** 固定时刻：2023-11-14T22:13:20Z。 */
    private static final long EPOCH = 1700000000000L;

    /** 手写假配置：TimeFmt 只用到 configString，其余方法给不出力。 */
    private static BotConfig cfg(Map<String, String> values) {
        return new BotConfig() {
            @Override
            public String configString(String path, String def) {
                return values.getOrDefault(path, def);
            }

            @Override
            public int configInt(String path, int def) {
                return def;
            }

            @Override
            public boolean configBool(String path, boolean def) {
                return def;
            }

            @Override
            public List<String> configStringList(String path) {
                return List.of();
            }
        };
    }

    @BeforeEach
    void resetWarnDedup() {
        TimeFmt.resetWarnings();
    }

    @Test
    void listFormatFollowsConfiguredPatternAndZone() {
        BotConfig cfg = cfg(Map.of(
                "display.list-time-format", "yyyy-MM-dd HH:mm:ss",
                TimeFmt.ZONE_KEY, "UTC"));
        List<String> warnings = new ArrayList<>();

        String out = TimeFmt.format(Preset.LIST, EPOCH, cfg, warnings::add);

        assertEquals("2023-11-14 22:13:20", out);
        assertTrue(warnings.isEmpty(), "合法配置不应告警");
    }

    @Test
    void badPatternFallsBackToPresetDefaultAndNamesRealKey() {
        BotConfig cfg = cfg(Map.of(
                "display.list-time-format", "qqq###",
                TimeFmt.ZONE_KEY, "UTC"));
        List<String> warnings = new ArrayList<>();

        String out = TimeFmt.format(Preset.LIST, EPOCH, cfg, warnings::add);

        assertEquals("2023-11-14 22:13", out, "坏模式串必须回退 LIST 的默认模式串");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("display.list-time-format"),
                "告警必须点名真实配置键，实际: " + warnings.get(0));
    }

    @Test
    void badZoneFallsBackToSystemZoneAndWarnsOnce() {
        BotConfig cfg = cfg(Map.of(TimeFmt.ZONE_KEY, "Mars/Olympus"));
        List<String> warnings = new ArrayList<>();

        String first = TimeFmt.format(Preset.LIST, EPOCH, cfg, warnings::add);
        String second = TimeFmt.format(Preset.LIST, EPOCH, cfg, warnings::add);

        String expected = DateTimeFormatter.ofPattern(Preset.LIST.defaultPattern())
                .format(Instant.ofEpochMilli(EPOCH).atZone(ZoneId.systemDefault()));
        assertEquals(expected, first, "坏时区必须回退服务器时区");
        assertEquals(first, second);
        assertEquals(1, warnings.size(), "同一坏时区只应告警一次");
        assertTrue(warnings.get(0).contains(TimeFmt.ZONE_KEY));
    }

    @Test
    void formatSafeReturnsUnknownForBrokenTimestamps() {
        BotConfig cfg = cfg(Map.of(TimeFmt.ZONE_KEY, "UTC"));
        List<String> warnings = new ArrayList<>();

        assertEquals(TimeFmt.UNKNOWN_TIME, TimeFmt.formatSafe(Preset.LIST, null, cfg, warnings::add));
        assertEquals(TimeFmt.UNKNOWN_TIME, TimeFmt.formatSafe(Preset.LIST, "", cfg, warnings::add));
        assertEquals(TimeFmt.UNKNOWN_TIME, TimeFmt.formatSafe(Preset.LIST, "abc", cfg, warnings::add));
        assertEquals(TimeFmt.UNKNOWN_TIME, TimeFmt.formatSafe(Preset.LIST, "99999999999999999999", cfg, warnings::add));
        assertEquals("时间未知", TimeFmt.UNKNOWN_TIME);
        assertTrue(warnings.isEmpty(), "脏时间戳静默降级，不告警");
    }

    @Test
    void formatSafeMatchesFormatForValidTimestamp() {
        BotConfig cfg = cfg(Map.of(TimeFmt.ZONE_KEY, "UTC"));
        List<String> warnings = new ArrayList<>();

        String safe = TimeFmt.formatSafe(Preset.LIST, "1700000000000", cfg, warnings::add);

        assertEquals(TimeFmt.format(Preset.LIST, EPOCH, cfg, warnings::add), safe);
        assertEquals("2023-11-14 22:13", safe);
    }

    @Test
    void presetsHaveDistinctPatternKeysAndSharedZoneKey() {
        assertNotEquals(Preset.EXPIRE.patternKey(), Preset.LIST.patternKey());
        assertEquals("bind.time-format", Preset.EXPIRE.patternKey());
        assertEquals("display.list-time-format", Preset.LIST.patternKey());
        assertEquals(TimeFmt.FALLBACK_PATTERN, Preset.EXPIRE.defaultPattern());
        assertEquals("yyyy-MM-dd HH:mm", Preset.LIST.defaultPattern());
        // 时区全服一处：两个 preset 共用同一个 ZONE_KEY，没有各自的时区键
        assertEquals("bind.time-zone", TimeFmt.ZONE_KEY);
    }
}
