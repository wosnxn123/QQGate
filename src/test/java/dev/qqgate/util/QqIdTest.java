package dev.qqgate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QqId} 契约回归：每一种拒绝输入都对应历史上真实可能的坏输入。
 * <p>重点守住两条：20 位纯数字（旧代码里 {@code Long.parseLong} 抛
 * {@link NumberFormatException} 冒到 Bukkit 的崩溃输入）必须被长度闸门拦下；
 * {@code isValid} 与 {@code parse} 恒同判，不许再出现两套 {@code isNumeric}。
 * 纯 Java（无 Bukkit）。
 */
class QqIdTest {

    @Test
    void nullAndBlankAreRejected() {
        assertTrue(QqId.parse(null).isEmpty(), "null 必须拒绝");
        assertTrue(QqId.parse("").isEmpty(), "空串必须拒绝");
        assertTrue(QqId.parse("   ").isEmpty(), "全空白必须拒绝");
    }

    @Test
    void plainNumberRoundTrips() {
        QqId id = QqId.parse("12345").orElseThrow();
        assertEquals(12345L, id.value());
        assertEquals("12345", id.digits());
        assertEquals("12345", id.toString(), "toString 必须是纯数字串，供玩家文案直接拼接");
    }

    @Test
    void eighteenDigitsIsTheInclusiveUpperBound() {
        // 18 位全 9 仍在闸门内（< Long.MAX_VALUE 的 19 位量级）。
        assertEquals(999_999_999_999_999_999L,
                QqId.parse("999999999999999999").orElseThrow().value());
        // 19 位：超过长度闸门，必须拒。
        assertTrue(QqId.parse("1000000000000000000").isEmpty(), "19 位必须被长度闸门拦下");
        // 20 位纯数字：历史上让 Long.parseLong 抛 NumberFormatException 的崩溃输入。
        assertTrue(QqId.parse("99999999999999999999").isEmpty(), "20 位必须被长度闸门拦下");
    }

    @Test
    void nonAsciiDigitCharactersAreRejected() {
        assertTrue(QqId.parse("+8612345").isEmpty(), "加号不是 QQ 号的一部分");
        assertTrue(QqId.parse("-123").isEmpty(), "QQ 号没有负数");
        assertTrue(QqId.parse("123abc").isEmpty(), "字母混入必须拒绝");
        assertTrue(QqId.parse("12 34").isEmpty(), "内部空格不能被两侧 trim 掩盖");
        assertTrue(QqId.parse("１２３").isEmpty(),
                "全角数字必须拒绝——Character.isDigit 会放过它们，旧 isNumeric 正是这么漏的");
    }

    @Test
    void zeroIsRejected() {
        assertTrue(QqId.parse("0").isEmpty(), "QQ 号不可能是 0");
        assertTrue(QqId.parse("000").isEmpty(), "全零输入同样不是合法 QQ 号");
    }

    @Test
    void leadingZerosAreNormalized() {
        QqId id = QqId.parse("0123456").orElseThrow();
        assertEquals(123456L, id.value());
        assertEquals("123456", id.digits(), "前导零必须归一");
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals(12345L, QqId.parse(" 12345 ").orElseThrow().value(), "两侧空白必须被 trim");
    }

    @Test
    void isValidAgreesWithParse() {
        String[] samples = {"12345", "99999999999999999999", " 12345 ", "-123", null};
        for (String raw : samples) {
            assertEquals(QqId.parse(raw).isPresent(), QqId.isValid(raw),
                    "isValid 必须与 parse 同判：" + raw);
        }
    }
}
