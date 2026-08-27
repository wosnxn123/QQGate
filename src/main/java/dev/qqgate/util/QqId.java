package dev.qqgate.util;

import java.util.Optional;

/**
 * QQ 号值类型：把「校验 + 解析」合成一步，调用方拿到的要么是合法 {@link QqId}，
 * 要么是 {@link Optional#empty()}，中间没有「忘了校验」的缝。
 * <p>存在理由：QQ 号原先是裸 {@code String} / {@code long} 约定——调用点先
 * {@code isNumeric(x)}，紧跟 {@code Long.parseLong(x)}，两步之间没有任何类型约束。
 * 仓库里两处 {@code isNumeric} 实现还不一致（一处有 18 位长度闸门，一处没有），
 * 于是游戏内传 20 位纯数字时 {@code Long.parseLong} 直接抛
 * {@link NumberFormatException} 冒到 Bukkit。值类型消灭了这条缝：
 * {@link #parse(String)} 永不抛异常，{@link Optional} 让调用方无从跳过校验。
 * <p>归一：前导零合法但会被吃掉——{@code parse("0123456")} 解析成功，
 * {@link #digits()} 返回 {@code "123456"}。
 */
public record QqId(long value) {

    /**
     * 长度闸门：{@code Long.MAX_VALUE} 是 19 位数，所以不超过 18 位的纯数字
     * 必然不溢出 {@link Long#parseLong(String)}。
     * 这是 {@link #parse(String)} 不需要 try/catch 的不变量。
     */
    public static final int MAX_DIGITS = 18;

    /**
     * 解析 QQ 号。拒绝顺序：
     * <ol>
     * <li>{@code null} / 空 / 全空白（先 {@code trim()}）；
     * <li>trim 后长度超过 {@link #MAX_DIGITS}——溢出闸门，先行于任何数值解析；
     * <li>含任何非 ASCII 数字字符：{@code +}、{@code -}、内部空格、全角数字、
     * 其他 Unicode 数字一概拒绝。注意不能用 {@code Character.isDigit}，
     * 它对全角/阿拉伯-印度数字返回 {@code true}，正是旧 {@code isNumeric}
     * 不一致的根源之一；
     * <li>数值不合法：纯数字串解析后 {@code value <= 0} 只能是 {@code "0"} /
     * {@code "000"} 这类全零输入，QQ 号不可能是 0 或负数。
     * </ol>
     * 两侧空白被 {@code trim()} 吃掉；前导零合法，解析后自然归一（见类注释）。
     *
     * @param raw 原始输入，可为 {@code null}
     * @return 合法 QQ 号，或 {@link Optional#empty()}；永不抛异常
     */
    public static Optional<QqId> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        // 长度闸门已保证 ≤18 位 ASCII 纯数字，parseLong 不可能溢出：无需 try/catch。
        long v = Long.parseLong(s);
        return v > 0 ? Optional.of(new QqId(v)) : Optional.empty();
    }

    /** 是否为合法 QQ 号；与 {@code parse(raw).isPresent()} 恒同判。 */
    public static boolean isValid(String raw) {
        return parse(raw).isPresent();
    }

    /** 归一后的十进制数字串（无前导零）。 */
    public String digits() {
        return String.valueOf(value);
    }

    /**
     * 直接返回数字串。record 默认的 {@code QqId[value=123]} 拼进玩家可见文案
     * 会很丑，必须覆写。
     */
    @Override
    public String toString() {
        return digits();
    }
}
