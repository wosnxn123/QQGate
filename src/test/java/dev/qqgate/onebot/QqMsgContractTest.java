package dev.qqgate.onebot;

import dev.qqgate.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QqMsg/MsgRenderer 契约测试：默认模板自洽、路径唯一、替换行为
 * （全局字段原样保留、缺字段 → 空串 + 告警一次）、启动期未知占位符校验。
 * 用假 MapConfig 驱动，不碰 Bukkit。
 */
class QqMsgContractTest {

    /** 假配置：只有 strings 段参与，其余方法回退默认值。 */
    static final class MapConfig implements BotConfig {
        final Map<String, String> strings = new HashMap<>();

        @Override
        public String configString(String path, String def) {
            return strings.getOrDefault(path, def);
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
    }

    /**
     * 每个默认模板自身必须合法：默认文案里出现未声明占位符就是
     * {reason_part} 事故的复刻（模板带洞、代码从不替换、用户看到裸花括号）。
     */
    @Test
    void everyDefaultTemplateOnlyUsesDeclaredFields() {
        for (QqMsg key : QqMsg.values()) {
            List<String> unknown = MsgRenderer.unknownTokens(key, key.def());
            assertTrue(unknown.isEmpty(),
                    key.name() + " 默认模板含未声明占位符: " + unknown);
        }
    }

    /** 路径唯一且都在 messages 命名空间：重复路径会让两个文案键互相覆盖配置。 */
    @Test
    void pathsAreUniqueAndUnderMessagesNamespace() {
        Set<String> seen = new HashSet<>();
        for (QqMsg key : QqMsg.values()) {
            assertTrue(key.path().startsWith("messages."),
                    key.name() + " 路径不在 messages 命名空间: " + key.path());
            assertTrue(seen.add(key.path()), "配置路径重复: " + key.path());
        }
    }

    /** AT/SENDER 是全局字段：构造期统一并入，任何键都不得遗漏。 */
    @Test
    void globalFieldsArePresentInEveryKey() {
        for (QqMsg key : QqMsg.values()) {
            assertTrue(key.fields().contains(QqMsg.Field.AT), key.name() + " 缺全局字段 AT");
            assertTrue(key.fields().contains(QqMsg.Field.SENDER), key.name() + " 缺全局字段 SENDER");
        }
    }

    /** render 替换声明字段；{at}/{sender} 原样留给 reply() 按通道注入。 */
    @Test
    void renderSubstitutesDeclaredFieldsAndPreservesGlobals() {
        MapConfig cfg = new MapConfig();
        MsgRenderer r = new MsgRenderer(cfg, s -> fail("不应告警: " + s));

        String out = r.render(QqMsg.ADMIN_UNBIND_EXACT_OK, Map.of(
                QqMsg.Field.PLAYER, "Steve",
                QqMsg.Field.QQ, "123456",
                QqMsg.Field.COUNT, "2"));
        assertEquals("{at} 已解绑 Steve <-> QQ 123456（还剩 2 条）", out);

        // 用户模板同时含两个全局字段：都不许动
        cfg.strings.put(QqMsg.QQUNBAN_OK.path(), "{at} 已解除拉黑 QQ {qq}（操作者 {sender}）");
        String out2 = r.render(QqMsg.QQUNBAN_OK, Map.of(QqMsg.Field.QQ, "777"));
        assertEquals("{at} 已解除拉黑 QQ 777（操作者 {sender}）", out2);

        // 无业务字段的键走无参重载
        assertEquals(QqMsg.WRONG_CODE.def(), r.render(QqMsg.WRONG_CODE));
    }

    /** 声明而调用方未给的字段 → 渲染空串，且每个（键，占位符）只告警一次。 */
    @Test
    void missingDeclaredFieldRendersEmptyAndWarnsExactlyOnce() {
        MapConfig cfg = new MapConfig();
        List<String> warns = new ArrayList<>();
        MsgRenderer r = new MsgRenderer(cfg, warns::add);

        String out = r.render(QqMsg.COOLDOWN, Map.of());
        assertEquals("{at} 操作太频繁，请  秒后再试", out);

        r.render(QqMsg.COOLDOWN, Map.of()); // 重复渲染不重复告警
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains(QqMsg.COOLDOWN.path()),
                "告警文案应点名配置键: " + warns.get(0));
        assertTrue(warns.get(0).contains(QqMsg.Field.SECONDS.token()),
                "告警文案应点名占位符: " + warns.get(0));
    }

    /** 用户模板含未知占位符 → validateAll 逐 token 告警，文案带键名与支持的占位符。 */
    @Test
    void validateAllFlagsUnknownTokenWithActionableMessage() {
        MapConfig cfg = new MapConfig();
        // 复刻旧事故模板：{reason_part} 从不被任何代码替换
        cfg.strings.put(QqMsg.QQBAN_OK.path(), "{at} 已拉黑 QQ {qq}{reason_part}");

        List<String> warns = new ArrayList<>();
        new MsgRenderer(cfg).validateAll(warns::add);

        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("{reason_part}"), warns.get(0));
        assertTrue(warns.get(0).contains(QqMsg.QQBAN_OK.path()), warns.get(0));
        assertTrue(warns.get(0).contains(QqMsg.Field.REASON.token()),
                "告警应列出该键支持的占位符: " + warns.get(0));
    }

    /** 用户模板故意省略声明字段是合法自定义，渲染与校验都不该告警。 */
    @Test
    void omittingDeclaredFieldInUserTemplateIsLegalCustomization() {
        MapConfig cfg = new MapConfig();
        cfg.strings.put(QqMsg.QQBAN_OK.path(), "{at} 已拉黑 QQ {qq}，如有异议请联系管理员");

        List<String> warns = new ArrayList<>();
        MsgRenderer r = new MsgRenderer(cfg, warns::add);
        String out = r.render(QqMsg.QQBAN_OK, Map.of(
                QqMsg.Field.QQ, "123456",
                QqMsg.Field.REASON, "（原因: 广告）"));
        assertEquals("{at} 已拉黑 QQ 123456，如有异议请联系管理员", out);

        r.validateAll(warns::add);
        assertTrue(warns.isEmpty(), "省略声明占位符不应告警: " + warns);
    }

    /** Field.token() 的下划线映射约定。 */
    @Test
    void fieldTokensAreLowerUnderscoreWrappedInBraces() {
        assertEquals("{old_player}", QqMsg.Field.OLD_PLAYER.token());
        assertEquals("{self_id}", QqMsg.Field.SELF_ID.token());
        assertEquals("{at}", QqMsg.Field.AT.token());
        assertEquals("{sender}", QqMsg.Field.SENDER.token());
    }
}
