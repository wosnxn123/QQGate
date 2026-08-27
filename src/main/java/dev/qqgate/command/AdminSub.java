package dev.qqgate.command;

import java.util.Locale;
import java.util.Optional;

/**
 * /qqgateadmin 子指令表 —— 派发、帮助面板、Tab 补全的唯一来源。
 * <p>新增子指令只在这里加一项：帮助与补全自动跟上；忘了在 onCommand 接线也会落到
 * {@code default -> sendHelp}，绝不静默（v1.5.5 及之前缺 help/default 分支，
 * 裸 {@code /qqgateadmin} 与 {@code /qqgateadmin help} 无任何输出）。
 */
public enum AdminSub {
    STATUS("", "连接状态与统计"),
    DIAG("", "运行时自检（配置+连接+文件，含健康警告）"),
    CODES("", "当前待验证的验证码"),
    LOOKUP("<玩家名|QQ号>", "双向查询绑定（被拉黑QQ带标记）"),
    UNBIND("<玩家名|QQ号> [QQ号]", "解绑，多条会列出；带第二参精确解绑"),
    UNBINDALL("<玩家名|QQ号>", "清空目标名下全部绑定"),
    BIND("<玩家名> <QQ号>", "代绑，跳过验证码但仍走限额裁决"),
    QQBAN("<QQ号> [原因]", "拉黑QQ，名下与同名账号一并封锁"),
    QQUNBAN("<QQ号>", "解除拉黑，被封账号自动复原"),
    QQBANS("", "QQ黑名单列表（含名下账号名）"),
    RELOAD("", "重载配置（连接参数需重启）"),
    HELP("", "显示本帮助");

    private final String args;
    private final String desc;

    AdminSub(String args, String desc) {
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

    /** 大小写不敏感解析；未知子指令返回空（调用方负责提示 + 显示帮助）。 */
    public static Optional<AdminSub> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(HELP);
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        for (AdminSub s : values()) {
            if (s.token().equals(token)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
