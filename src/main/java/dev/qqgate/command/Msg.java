package dev.qqgate.command;

/**
 * 游戏内指令输出的统一样式。
 * <p>纯字符串拼装，不依赖 Bukkit，便于单测；所有面向玩家/管理员的回执都从这里取样式，
 * 避免同一插件里出现三种前缀、五种颜色。
 * <p>约定：单行回执带 {@link #PREFIX}；列表用 {@link #title} + {@link #item}；
 * 面板用 {@link #header} + 内容 + {@link #footer}（面板内不再重复前缀，省聊天栏行宽）。
 */
public final class Msg {

    private Msg() {
    }

    /** 单行回执前缀。 */
    public static final String PREFIX = "§6QQGate §8» §r";

    /** 成功。 */
    public static String ok(String body) {
        return PREFIX + "§a" + body;
    }

    /** 中性信息 / 查无结果。 */
    public static String info(String body) {
        return PREFIX + "§7" + body;
    }

    /** 警告（需要注意但操作已完成）。 */
    public static String warn(String body) {
        return PREFIX + "§e⚠ §f" + body;
    }

    /** 失败 / 拒绝。 */
    public static String err(String body) {
        return PREFIX + "§c" + body;
    }

    /** 用法提示：参数签名为空时只显示命令本体。 */
    public static String usage(String command, String args) {
        return PREFIX + "§c用法: §f/" + command + (args == null || args.isEmpty() ? "" : " §e" + args);
    }

    /** 列表/段落标题，后接 {@link #item} 或 {@link #field}。 */
    public static String title(String body) {
        return PREFIX + "§f" + body;
    }

    /** 列表条目，note 为灰色附注（可空）。 */
    public static String item(String body, String note) {
        return "§8 ▸ §f" + body + (note == null || note.isEmpty() ? "" : " §8· §7" + note);
    }

    /** 编号列表条目，用于「有多条、请挑一条」的场景。 */
    public static String item(int index, String body, String note) {
        return "§8 " + index + ". §f" + body + (note == null || note.isEmpty() ? "" : " §8· §7" + note);
    }

    /** 键值行，用于 status / diag。 */
    public static String field(String name, String value) {
        return "§8 ▸ §7" + name + " §8: §f" + value;
    }

    /** 引导下一步操作的尾行。 */
    public static String hint(String body) {
        return "§8   ↳ §7" + body;
    }

    /** 面板首行。 */
    public static String header(String heading) {
        return "§8──── §6§lQQGate §8· §f" + heading + " §8────";
    }

    /** 面板末行。 */
    public static String footer(String note) {
        return "§8└─ §7" + note;
    }

    /** 帮助面板里的一条指令行。 */
    public static String cmdRow(String command, String args, String desc) {
        return "§8 ▸ §e/" + command + (args == null || args.isEmpty() ? "" : " §f" + args)
                + " §8· §7" + desc;
    }
}
