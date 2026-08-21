package dev.qqgate.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 踢出页 {reason} 渲染契约：原因按纯文本显示，内容无法污染模板样式。
 * <p>背景：拉黑原因由管理员自由输入，踢出页模板本身是 MiniMessage。
 * 若原因未转义，{@code <red>} 等标签会被解析成样式、未闭合标签会污染模板后半段。
 */
class JoinListenerKickRenderTest {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void reasonTagsAreEscapedToPlainText() {
        // escapeTags 只转义 '<'（闭合标签因含 '<' 一并失效），反斜杠转义自身
        assertEquals("（\\<red>外挂）", JoinListener.reasonFragment("<red>外挂"));
    }

    @Test
    void reasonRoundTripsVerbatimThroughMiniMessage() {
        // 契约：任意原因文本经转义+deserialize 后原样还原（含反斜杠、冒号等特殊字符）
        String weird = "<red>a\\b:点我</red>";
        Component rendered = MM.deserialize(JoinListener.reasonFragment(weird));
        assertEquals("（" + weird + "）", PLAIN.serialize(rendered));
    }

    @Test
    void blankReasonYieldsEmptyFragment() {
        assertEquals("", JoinListener.reasonFragment(null));
        assertEquals("", JoinListener.reasonFragment("   "));
    }

    @Test
    void escapedReasonDeserializesAsLiteralTextWithoutStyleBleed() {
        String template = "<red><b>该账号已被封禁</b></red>\n<gray>原因：{reason}</gray>";
        String composed = template.replace("{reason}", JoinListener.reasonFragment("<red>作弊</red>开挂"));
        Component rendered = MM.deserialize(composed);
        // 原因以字面文本出现在全角括号内，且不改变模板自身的灰色段落
        assertEquals("该账号已被封禁\n原因：（<red>作弊</red>开挂）", PLAIN.serialize(rendered));
    }

    @Test
    void unclosedTagInReasonCannotBleedIntoTemplate() {
        String template = "<gray>原因：{reason}\n申诉请联系管理员</gray>";
        String composed = template.replace("{reason}", JoinListener.reasonFragment("<red>"));
        Component rendered = MM.deserialize(composed);
        assertEquals("原因：（<red>）\n申诉请联系管理员", PLAIN.serialize(rendered));
    }
}
