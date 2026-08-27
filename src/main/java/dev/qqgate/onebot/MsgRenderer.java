package dev.qqgate.onebot;

import dev.qqgate.BotConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 侧回复文案渲染器：QqMsg 模板取读与占位符替换的唯一入口。
 *
 * <p>职责边界：
 * <ul>
 *   <li>替换键声明的**非全局**字段；{@link QqMsg.Field#AT}/{@link QqMsg.Field#SENDER}
 *       原样保留，由 ChatMessageHandler.reply() 按群（@发送者）/私聊（空串）注入。</li>
 *   <li>声明了却没提供的字段 → 替换成空串并告警一次（不抛异常：一条文案配错
 *       不该让整条指令失败）。</li>
 *   <li>模板里的未知 token 不在渲染期处理——那是启动期
 *       {@link #validateAll(Consumer)} 的事。</li>
 *   <li>时间格式不经过这里：条目里的时间是调用方用 TimeFmt.Preset.LIST
 *       渲染好再传入的字符串。</li>
 * </ul>
 */
public final class MsgRenderer {

    /** {xxx} 占位符形态（小写字母 + 下划线）。只在校验路径用；渲染走 String.replace 链。 */
    private static final Pattern TOKEN = Pattern.compile("\\{[a-z_]+\\}");

    private final BotConfig config;
    private final Consumer<String> warn;
    /** 已告警过的 "配置路径|占位符"；去重，缺字段不随消息量刷屏。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** 无告警回调构造：缺字段静默渲染为空串（如只关心渲染结果的场景）。 */
    public MsgRenderer(BotConfig config) {
        this(config, ignored -> {
        });
    }

    /**
     * @param warn 缺字段告警回调；每个（键，占位符）组合最多触发一次。
     */
    public MsgRenderer(BotConfig config, Consumer<String> warn) {
        this.config = config;
        this.warn = warn;
    }

    /** 无业务字段的键（帮助/冷却等）的便捷重载。 */
    public String render(QqMsg key) {
        return render(key, Map.of());
    }

    /**
     * 取模板（用户配置优先，缺失回退 {@link QqMsg#def()}），替换全部非全局字段。
     * {@code {at}}/{@code {sender}} 绝不替换；声明而 values 未给的字段替换为空串
     * 并告警一次（键名 + 占位符）。
     */
    public String render(QqMsg key, Map<QqMsg.Field, String> values) {
        String out = config.configString(key.path(), key.def());
        for (QqMsg.Field f : key.fields()) {
            if (f == QqMsg.Field.AT || f == QqMsg.Field.SENDER) {
                continue; // 全局字段：由 reply() 按通道注入
            }
            String v = values.get(f);
            if (v == null) {
                v = "";
                if (warned.add(key.path() + "|" + f.token())) {
                    warn.accept("文案 " + key.path() + " 的占位符 " + f.token()
                            + " 未被提供，已按空串渲染");
                }
            }
            out = out.replace(f.token(), v);
        }
        return out;
    }

    /**
     * 启动期校验：遍历全部 QqMsg 键，读用户实际配置的模板（缺失时用默认模板），
     * 每个未知占位符告警一句，文案点名配置键、未知占位符与该键支持的占位符全集。
     *
     * <p>动机：模板与代码脱节曾酿成真实 bug——messages.qqban-ok 的默认模板带
     * {reason_part}/{cleared_part}，而代码从不替换它们，用户直接看到裸花括号。
     * 模板改由配置驱动后，只有启动期按"允许字段集"逐个比对，才能把这类
     * 死占位符/拼错占位符在第一条消息发出之前抓出来。
     *
     * <p>省略已声明的占位符是合法自定义（管理员可以删掉不想展示的信息），
     * 不告警；只有模板里出现该键不认识的 token 才是错。
     */
    public void validateAll(Consumer<String> warn) {
        for (QqMsg key : QqMsg.values()) {
            String template = config.configString(key.path(), key.def());
            for (String tok : unknownTokens(key, template)) {
                warn.accept("配置 " + key.path() + " 含未知占位符 " + tok
                        + "，该键支持的占位符：" + supportedTokens(key) + "，请删除或更正");
            }
        }
    }

    /**
     * 扫出模板里所有 {@code {xxx}} 形态 token 中不在 {@code key.fields()} 的那些。
     * 去重、按首次出现顺序返回。默认模板自身合法性由测试对全部常量断言。
     */
    public static List<String> unknownTokens(QqMsg key, String template) {
        Set<String> known = new HashSet<>();
        for (QqMsg.Field f : key.fields()) {
            known.add(f.token());
        }
        List<String> unknown = new ArrayList<>();
        Matcher m = TOKEN.matcher(template);
        while (m.find()) {
            String tok = m.group();
            if (!known.contains(tok) && !unknown.contains(tok)) {
                unknown.add(tok);
            }
        }
        return unknown;
    }

    /** 该键全部字段的占位符（声明序），用于告警文案直接指导修改。 */
    private static String supportedTokens(QqMsg key) {
        StringBuilder sb = new StringBuilder();
        for (QqMsg.Field f : key.fields()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(f.token());
        }
        return sb.toString();
    }
}
