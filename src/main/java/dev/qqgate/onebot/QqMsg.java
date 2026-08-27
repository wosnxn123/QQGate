package dev.qqgate.onebot;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * QQ 侧回复文案契约：配置路径 + 默认模板 + 允许占位符集合，一处定义。
 *
 * <p>── 为什么存在 ──
 * 旧形态是散在各分支里的 {@code msg("messages.X", "默认")} + 手写 .replace() 链：
 * 同一个键被多个分支共用且默认文案不一致（admin-unbind-notfound 四个分支三套占位符），
 * 模板里出现代码从不替换的死占位符也没人发现（messages.qqban-ok 的 {reason_part}
 * 曾让用户直接看到裸花括号）。本枚举把"键 → 语义 → 字段集"固化成编译期可见的契约，
 * 配合 {@link MsgRenderer#validateAll} 在启动期校验用户模板。
 *
 * <p>── 规则 ──
 * <ul>
 *   <li>一个键一套语义一套字段。语义不同的分支必须拆键
 *       （如 ADMIN_UNBIND_NOTFOUND 与 ADMIN_UNBIND_NOTFOUND_EXACT）。</li>
 *   <li>列表排版不再塞进单个 {result} 洞：拆成"头 + 条目（+ 提示）"多键，
 *       由调用方按 \n 拼接，管理员才能自定义排版。</li>
 *   <li>{qq} 只表示业务目标 QQ；{sender} 表示发送者 QQ。要显示发送者必须用 {sender}——
 *       旧代码靠 reply() 兜底把漏替换的 {qq} 换成发送者，错了都没人发现。</li>
 *   <li>片段型字段（REASON/NAMES/DETAIL）：调用方预先拼好的展示片段，
 *       无条件时传空串；括号、分隔符等装饰由片段自带（见各键注释与 qqmsg-keys 清单）。</li>
 * </ul>
 *
 * <p>── 全局字段 ──
 * {@link Field#AT} 与 {@link Field#SENDER} 自动并入每个键的 fields()，
 * {@link MsgRenderer#render} 绝不替换它们——由 ChatMessageHandler.reply() 按
 * 群（@发送者）/私聊（空串）分别注入。
 */
public enum QqMsg {

    // ============================ 玩家文案 ============================

    /** 绑定成功。 */
    BOUND("messages.bound", """
            {at} 绑定成功！
            游戏账号：{player}
            该QQ当前已绑定 {count}/{max} 个账号，还可绑定 {remaining} 个
            现在可以重新进入服务器了""",
            Field.PLAYER, Field.COUNT, Field.MAX, Field.REMAINING),

    /** 绑定成功（replace 策略挤掉最早绑定）。 */
    REPLACED("messages.replaced", """
            {at} 绑定成功（已自动替换旧绑定，挤下：{old_player}）
            游戏账号：{player}
            该QQ当前已绑定 {count}/{max} 个账号，还可绑定 {remaining} 个""",
            Field.OLD_PLAYER, Field.PLAYER, Field.COUNT, Field.MAX, Field.REMAINING),

    /** 重复绑定已绑过的码 / 代绑已存在的组合。 */
    ALREADY_BOUND("messages.already-bound", "{at} 该QQ已绑定游戏账号 {player}，无需重复绑定",
            Field.PLAYER),

    WRONG_CODE("messages.wrong-code", "{at} 验证码错误或已过期，请重新进入服务器获取"),

    CODE_USED("messages.code-used", "{at} 该验证码已被使用"),

    QQ_FULL("messages.qq-full", "{at} 该QQ已绑定满 {max} 个账号（{count}/{max}），请联系管理员",
            Field.COUNT, Field.MAX),

    PLAYER_FULL("messages.player-full", "{at} 该游戏账号已绑定满 {max} 个QQ，请联系管理员",
            Field.MAX),

    COOLDOWN("messages.cooldown", "{at} 操作太频繁，请 {seconds} 秒后再试",
            Field.SECONDS),

    /** REASON = BindService.reasonPart 片段："（原因）"，无原因为空串。 */
    QQ_BANNED("messages.qq-banned", "{at} 该QQ已被服务器拉黑{reason}，无法绑定；如有异议请联系管理员",
            Field.REASON),

    /** 查询/解绑时名下无绑定。 */
    NOT_BOUND("messages.not-bound", "{at} 你当前没有绑定任何账号"),

    SELF_UNBIND_OK("messages.self-unbind-ok", "{at} 已解绑账号 {player}（该QQ还剩 {count} 个绑定）",
            Field.PLAYER, Field.COUNT),

    SELF_UNBIND_NOTFOUND("messages.self-unbind-notfound",
            "{at} 你名下没有名为 {player} 的绑定，发送「解绑」查看列表",
            Field.PLAYER),

    /** 玩家绑定列表·头。原 messages.self-unbind-list 的 {result} 拆解产物。 */
    SELF_UNBIND_LIST_HEADER("messages.self-unbind-list-header", "{at} 已绑定 {count}/{max} 个账号：",
            Field.COUNT, Field.MAX),

    /** 玩家绑定列表·条目。TIME 由调用方用 TimeFmt.Preset.LIST 渲染好传入。 */
    SELF_UNBIND_LIST_ITEM("messages.self-unbind-list-item", " {index}. {player}（{time}）",
            Field.INDEX, Field.PLAYER, Field.TIME),

    /** 玩家绑定列表·尾提示（仅 bind.self-unbind 开启时拼接）。 */
    SELF_UNBIND_LIST_HINT("messages.self-unbind-list-hint", "解绑指定账号：解绑 <账号名>"),

    /** 拉黑成功·主句。REASON = "（原因: xxx）"，无原因为空串。 */
    QQBAN_OK("messages.qqban-ok", "{at} 已拉黑 QQ {qq}{reason}",
            Field.QQ, Field.REASON),

    /** 拉黑成功·名下封锁段（仅 names 非空时拼接，独立成键管理员才能改或删）。 */
    QQBAN_OK_LOCKED("messages.qqban-ok-locked",
            "名下账号已封锁（绑定保留作案底）：{names}\n同名新连接将被拒绝；解拉黑后自动复原",
            Field.NAMES),

    QQUNBAN_OK("messages.qqunban-ok", "{at} 已解除拉黑 QQ {qq}",
            Field.QQ),

    QQUNBAN_NONE("messages.qqunban-none", "{at} QQ {qq} 不在黑名单",
            Field.QQ),

    /** 代绑时目标 QQ 在黑名单。 */
    ADMIN_BIND_BANNED("messages.admin-bind-banned", "{at} ⚠ QQ {qq} 已被拉黑，请先 解拉黑 {qq}",
            Field.QQ),

    QQBANS_EMPTY("messages.qqbans-empty", "{at} QQ 黑名单为空"),

    /** 黑名单列表·头。原 messages.qqbans-list 的 {result} 拆解产物。 */
    QQBANS_LIST_HEADER("messages.qqbans-list-header", "{at} QQ 黑名单（{count} 条）：",
            Field.COUNT),

    /**
     * 黑名单列表·条目。REASON/NAMES 为带前导分隔符的片段：
     * " · 原因文本" / " · 名下: 名1、名2"，缺省传空串（模板不留残分隔符）。
     */
    QQBANS_LIST_ITEM("messages.qqbans-list-item", "  {qq} · {time}{reason}{names}",
            Field.QQ, Field.TIME, Field.REASON, Field.NAMES),

    /** 玩家帮助（解绑行按 self-unbind 开关由调用方动态追加，不在此模板内）。 */
    HELP("messages.help", "{at} 可用指令：\n绑定 <验证码> —— 绑定游戏账号\n查询 —— 查看我的绑定"),

    // ============================ 管理员文案 ============================

    /** 管理员帮助（与玩家帮助合并显示时，{at} 由调用方剥掉）。 */
    ADMIN_HELP("messages.admin-help", """
            {at} 管理员指令：
            查 <玩家名|QQ号> —— 查询绑定（拉黑QQ带⚠标记）
            解绑 <玩家名|QQ号> —— 解绑（多条会列出）
            解绑 <玩家名> <QQ号> —— 精确解绑
            全解绑 <玩家名|QQ号> —— 清空全部绑定
            绑定 <玩家名> <QQ号> —— 代绑
            拉黑 <QQ号> [原因] —— 拉黑QQ（名下+同名账号全封锁）
            解拉黑 <QQ号> —— 解除拉黑（自动复原）
            拉黑列表 —— 黑名单（含名下账号名）
            状态 —— 连接与统计"""),

    /** 查 QQ 时目标在黑名单的首行警示。REASON = "（原因: xxx）"，无原因为空串。 */
    ADMIN_LOOKUP_BANNED_NOTE("messages.admin-lookup-banned-note", "⚠ 该QQ已被拉黑{reason}",
            Field.REASON),

    /** 按 QQ 查询·无绑定。原塞在 admin-lookup 的 {result} 里。 */
    ADMIN_LOOKUP_QQ_EMPTY("messages.admin-lookup-qq-empty", "{at} QQ {qq} 未绑定任何账号",
            Field.QQ),

    /** 按 QQ 查询·头（QQ → 账号方向）。 */
    ADMIN_LOOKUP_QQ_HEADER("messages.admin-lookup-qq-header", "{at} QQ {qq} 绑定 {count} 个账号：",
            Field.QQ, Field.COUNT),

    /** 按 QQ 查询·条目（列的是玩家名）。TIME 由调用方渲染好传入。 */
    ADMIN_LOOKUP_QQ_ITEM("messages.admin-lookup-qq-item", "  {player} · {time}",
            Field.PLAYER, Field.TIME),

    /** 按玩家名查询·头（玩家 → QQ 方向）。 */
    ADMIN_LOOKUP_PLAYER_HEADER("messages.admin-lookup-player-header", "{at} 玩家 {player} 绑定 {count} 个QQ：",
            Field.PLAYER, Field.COUNT),

    /** 按玩家名查询·条目（列的是 QQ）。TIME 由调用方渲染好传入。 */
    ADMIN_LOOKUP_PLAYER_ITEM("messages.admin-lookup-player-item", "  QQ {qq} · {time}",
            Field.QQ, Field.TIME),

    /** 按玩家名查询·无绑定。TARGET = 原样输入的查询目标。 */
    ADMIN_LOOKUP_EMPTY("messages.admin-lookup-empty", "{at} 玩家 {target} 未绑定QQ",
            Field.TARGET),

    /**
     * 单参解绑/全解绑无结果。TARGET = 原样输入的目标。
     * 与双参精确解绑未找到（字段集不同）拆键，不再共用一个键四套文案。
     */
    ADMIN_UNBIND_NOTFOUND("messages.admin-unbind-notfound", "{at} {target} 无绑定",
            Field.TARGET),

    /** 双参精确解绑未找到该组合。 */
    ADMIN_UNBIND_NOTFOUND_EXACT("messages.admin-unbind-notfound-exact",
            "{at} 未找到 {player} 与 QQ {qq} 的绑定",
            Field.PLAYER, Field.QQ),

    /** 解绑成功（单条直解/精确解绑共用；COUNT = 该玩家剩余绑定条数，可为 0）。 */
    ADMIN_UNBIND_EXACT_OK("messages.admin-unbind-exact-ok",
            "{at} 已解绑 {player} <-> QQ {qq}（还剩 {count} 条）",
            Field.PLAYER, Field.QQ, Field.COUNT),

    /** 多条绑定选择列表·头。TARGET = 调用方拼好的标签（QQ 方向为 "QQ 12345"，玩家方向为裸名）。 */
    ADMIN_UNBIND_AMBIGUOUS_HEADER("messages.admin-unbind-ambiguous-header",
            "{at} {target} 名下有 {count} 条绑定：",
            Field.TARGET, Field.COUNT),

    /** 多条绑定选择列表·条目。 */
    ADMIN_UNBIND_AMBIGUOUS_ITEM("messages.admin-unbind-ambiguous-item", " {index}. QQ {qq}（{player}）",
            Field.INDEX, Field.QQ, Field.PLAYER),

    /** 多条绑定选择列表·尾提示。 */
    ADMIN_UNBIND_AMBIGUOUS_HINT("messages.admin-unbind-ambiguous-hint",
            "精确解绑：解绑 <玩家名> <QQ号>\n清空全部：全解绑 <目标>"),

    /** 全解绑成功。DETAIL = 被清绑定用"、"连接的清单（按QQ解绑=玩家名，按玩家解绑=QQ号）。 */
    ADMIN_UNBINDALL_OK("messages.admin-unbindall-ok", "{at} 已清空 {target} 名下 {count} 条绑定（{detail}）",
            Field.TARGET, Field.COUNT, Field.DETAIL),

    /**
     * 代绑成功。挤下旧绑定时追加的"（挤下 X）"是条件性尾巴，
     * 由调用方拼接（旧代码把它塞进 def，用户自定义模板后直接丢失）。
     */
    ADMIN_BIND_OK("messages.admin-bind-ok", "{at} 已绑定 {player} <-> QQ {qq}",
            Field.PLAYER, Field.QQ),

    /** 代绑时目标玩家无任何绑定记录、无法定位 UUID。PLAYER 为必须替换字段（旧代码漏替换）。 */
    ADMIN_BIND_NO_PLAYER("messages.admin-bind-no-player",
            "{at} 未找到玩家 {player} 的既有绑定记录，无法定位UUID；请让玩家先进服一次，或使用游戏内 /qqgateadmin bind",
            Field.PLAYER),

    /** 代绑兜底失败（旧默认文案里的 {reason} 是从未被替换的死占位符，已清除）。 */
    ADMIN_BIND_FAIL("messages.admin-bind-fail", "{at} 绑定失败"),

    /** 状态查询：五项指标各占一个字段，管理员可自由排版（旧形态是一条裸字符串塞 {result}）。 */
    ADMIN_STATUS("messages.admin-status",
            "{at} mode={mode} connected={connected} self_id={self_id} binds={binds} active_codes={codes}",
            Field.MODE, Field.CONNECTED, Field.SELF_ID, Field.BINDS, Field.CODES);

    /**
     * 占位符字段。{@link #token()} 形如 {@code {old_player}}。
     * AT/SENDER 为全局字段：自动属于每个键，渲染器不替换，由回复通道注入。
     */
    public enum Field {
        /** 全局：@ 提及。群回复注入 at 码 + 换行，私聊替换为空串。 */
        AT,
        /** 全局：发送者 QQ。旧代码里漏替换的 {qq} 会被兜底换成发送者，错误被掩盖；现一律显式用 {sender}。 */
        SENDER,
        /** 游戏账号名。 */
        PLAYER,
        /** 业务目标 QQ（绑定/解绑/拉黑/查询的对象）。绝不表示发送者。 */
        QQ,
        /** 原样输入的查询/解绑目标（可能是玩家名，也可能是 QQ 号字符串）。 */
        TARGET,
        /** 条数（已绑数、剩余条数、黑名单条数等）。 */
        COUNT,
        /** 绑定上限。 */
        MAX,
        /** 还可绑定几个。 */
        REMAINING,
        /** 被替换绑定挤下的旧玩家名。 */
        OLD_PLAYER,
        /** 剩余冷却秒数。 */
        SECONDS,
        /** 预拼封禁原因片段；无原因传空串，模板不留残括号。括号/"原因:"等装饰由片段自带，各键不同。 */
        REASON,
        /** 被清空的绑定清单（玩家名或 QQ 号，用"、"连接）。 */
        DETAIL,
        /** 列表条目序号（第几条）。 */
        INDEX,
        /** 预格式化时间字符串：调用方用 TimeFmt.Preset.LIST 渲染好传入，渲染器不碰时间格式。 */
        TIME,
        /** 名下账号名清单（"、"连接）；黑名单条目里为带前导分隔符的片段。 */
        NAMES,
        /** OneBot 连接模式（reverse-ws / forward-ws）。 */
        MODE,
        /** 是否已连上机器人。 */
        CONNECTED,
        /** 机器人自身 QQ 号。 */
        SELF_ID,
        /** 绑定总数。 */
        BINDS,
        /** 活跃验证码数。 */
        CODES;

        /** 占位符形态：名字转小写下划线包花括号，如 OLD_PLAYER → {old_player}。 */
        public String token() {
            return "{" + name().toLowerCase(Locale.ROOT) + "}";
        }
    }

    private final String path;
    private final String def;
    private final Set<Field> fields;

    /** AT/SENDER 在此统一并入，任何常量不得手写、也无法遗漏全局字段。 */
    QqMsg(String path, String def, Field... extra) {
        this.path = path;
        this.def = def;
        EnumSet<Field> all = EnumSet.of(Field.AT, Field.SENDER);
        Collections.addAll(all, extra);
        this.fields = Collections.unmodifiableSet(all);
    }

    /** 配置路径（messages 命名空间，如 messages.bound）。 */
    public String path() {
        return path;
    }

    /** 配置缺失时的默认模板。只含本键声明字段的占位符（测试保证，防 {reason_part} 式死占位符）。 */
    public String def() {
        return def;
    }

    /** 允许字段集（含全局 AT/SENDER）；渲染与启动校验都以它为准。 */
    public Set<Field> fields() {
        return fields;
    }
}
