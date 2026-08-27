package dev.qqgate.command;

import dev.qqgate.admin.AdminOps;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指令层回归：子指令解析 + 目标解析 + Msg 样式契约。纯 Java（无 Bukkit）。
 * <p>子指令表是派发、帮助面板、Tab 补全的唯一来源，解析一旦漂移三处同时坏。
 */
class SubCommandTest {

    @Test
    void everyAdminSubRoundTripsThroughItsToken() {
        for (AdminSub s : AdminSub.values()) {
            assertEquals(s, AdminSub.of(s.token()).orElseThrow(), "token 必须解析回自身");
            assertEquals(s, AdminSub.of(s.token().toUpperCase(Locale.ROOT)).orElseThrow(),
                    "解析必须大小写不敏感");
            assertEquals(s, AdminSub.of("  " + s.token() + "  ").orElseThrow(),
                    "两侧空白必须被 trim");
            assertEquals(s.token(), s.name().toLowerCase(Locale.ROOT));
            assertFalse(s.desc().isBlank(), "帮助面板每行都要有说明：" + s);
            assertNotNull(s.args(), "无参子指令应为空串而非 null：" + s);
        }
    }

    @Test
    void everyPlayerSubRoundTripsThroughItsToken() {
        for (PlayerSub s : PlayerSub.values()) {
            assertEquals(s, PlayerSub.of(s.token()).orElseThrow());
            assertEquals(s, PlayerSub.of(s.token().toUpperCase(Locale.ROOT)).orElseThrow());
            assertFalse(s.desc().isBlank(), "帮助面板每行都要有说明：" + s);
        }
        assertEquals(PlayerSub.BIND, PlayerSub.of("BiNd").orElseThrow());
        assertEquals(PlayerSub.INFO, PlayerSub.of("info").orElseThrow());
    }

    @Test
    void blankInputIsHelpAndUnknownIsEmpty() {
        assertEquals(AdminSub.HELP, AdminSub.of(null).orElseThrow(), "无参 → 帮助面板");
        assertEquals(AdminSub.HELP, AdminSub.of("   ").orElseThrow());
        assertTrue(AdminSub.of("nope").isEmpty(), "未知子指令必须落空，由调用方提示");
        assertTrue(AdminSub.of("qq ban").isEmpty(), "带空格的伪 token 不得误命中");

        assertEquals(PlayerSub.HELP, PlayerSub.of(null).orElseThrow());
        assertEquals(PlayerSub.HELP, PlayerSub.of("").orElseThrow());
        assertTrue(PlayerSub.of("unbind").isEmpty(), "玩家指令没有 unbind，必须落空");
    }

    @Test
    void usageOmitsArgsWhenSignatureEmpty() {
        assertEquals(Msg.PREFIX + "§c用法: §f/qqgate", Msg.usage("qqgate", ""));
        assertEquals(Msg.PREFIX + "§c用法: §f/qqgate", Msg.usage("qqgate", null));
        assertTrue(Msg.usage("qqgate bind", "<验证码>").endsWith("§e<验证码>"));
    }

    @Test
    void listRowsDropEmptyNotes() {
        assertEquals("§8 ▸ §fSteve", Msg.item("Steve", ""));
        assertEquals("§8 ▸ §fSteve §8· §710001", Msg.item("Steve", "10001"));
        assertEquals("§8 1. §fSteve", Msg.item(1, "Steve", null));
        assertEquals("§8 ▸ §e/qqgate info §8· §7查看绑定", Msg.cmdRow("qqgate info", "", "查看绑定"));
    }

    @Test
    void singleLineRepliesCarryPrefix() {
        assertTrue(Msg.ok("done").startsWith(Msg.PREFIX));
        assertTrue(Msg.err("no").startsWith(Msg.PREFIX));
        assertTrue(Msg.warn("careful").startsWith(Msg.PREFIX));
        assertTrue(Msg.info("none").startsWith(Msg.PREFIX));
        // 面板行不带前缀（省行宽）
        assertFalse(Msg.header("状态").startsWith(Msg.PREFIX));
        assertFalse(Msg.field("连接", "已连接").startsWith(Msg.PREFIX));
    }

    @Test
    void overlongDigitTargetFallsBackToNameInsteadOfThrowing() {
        // 回归真实崩溃：旧实现的纯数字判定无位数闸门，20 位纯数字目标穿过判定后
        // 数值解析抛 NumberFormatException 直达 Bukkit（玩家看到红字内部错误）。
        // 现在统一走 QqId.parse 的 18 位闸门：超位纯数字落 NameTarget，全程无异常。
        AdminOps.Target over = AdminOps.target("99999999999999999999");
        assertInstanceOf(AdminOps.NameTarget.class, over);
        assertEquals("99999999999999999999", ((AdminOps.NameTarget) over).name());

        // 19 位（数值上未溢出 long，但超闸门）：同样落玩家名
        assertInstanceOf(AdminOps.NameTarget.class, AdminOps.target("1234567890123456789"));
        // 18 位 = 闸门上限：解析为 QQ 号
        AdminOps.Target edge = AdminOps.target("123456789012345678");
        assertInstanceOf(AdminOps.QqTarget.class, edge);
        assertEquals(123456789012345678L, ((AdminOps.QqTarget) edge).qq().value());
        // 空串 / 非数字：玩家名
        assertInstanceOf(AdminOps.NameTarget.class, AdminOps.target(""));
        assertInstanceOf(AdminOps.NameTarget.class, AdminOps.target("Steve"));
    }
}
