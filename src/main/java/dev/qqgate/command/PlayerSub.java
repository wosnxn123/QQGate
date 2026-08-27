package dev.qqgate.command;

import java.util.Locale;
import java.util.Optional;

/**
 * /qqgate 子指令表 —— 与 {@link AdminSub} 同构：派发、帮助、Tab 补全同源，
 * 帮助文本不会再和实际支持的子指令漂移。
 */
public enum PlayerSub {
    BIND("", "获取验证码，发到QQ群完成绑定"),
    INFO("", "查看自己的绑定状态"),
    HELP("", "显示本帮助");

    private final String args;
    private final String desc;

    PlayerSub(String args, String desc) {
        this.args = args;
        this.desc = desc;
    }

    /** 玩家实际输入的小写形态。 */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 参数签名，无参子指令为空串。 */
    public String args() {
        return args;
    }

    /** 一句话说明，用于帮助面板。 */
    public String desc() {
        return desc;
    }

    /** 大小写不敏感解析；空输入视为 HELP，未知子指令返回空。 */
    public static Optional<PlayerSub> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(HELP);
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        for (PlayerSub s : values()) {
            if (s.token().equals(token)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
