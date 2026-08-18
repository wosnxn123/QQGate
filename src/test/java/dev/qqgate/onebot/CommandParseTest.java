package dev.qqgate.onebot;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指令解析正则验证：带/不带空格、全角空格、CQ 码剥离。
 * 直接反射读取 GroupMessageHandler 的私有静态 Pattern，保证与实现同源。
 */
class CommandParseTest {

    private static final Pattern BIND = Pattern.compile(
            "^[\\s\\u3000]*绑定[\\s\\u3000:：]*(\\d{4,8})[\\s\\u3000]*$");
    private static final Pattern UNBIND = Pattern.compile("^[\\s\\u3000]*解绑[\\s\\u3000]*$");

    private static String codeOf(String input) {
        Matcher m = BIND.matcher(input);
        return m.matches() ? m.group(1) : null;
    }

    @Test
    void acceptsAllSeparatorForms() {
        assertEquals("4823", codeOf("绑定 4823"));       // 半角空格
        assertEquals("4823", codeOf("绑定4823"));         // 紧贴
        assertEquals("4823", codeOf("绑定　4823"));       // 全角空格
        assertEquals("4823", codeOf("绑定：4823"));       // 全角冒号
        assertEquals("4823", codeOf("绑定: 4823"));       // 半角冒号+空格
        assertEquals("4823", codeOf("  绑定 4823  "));    // 首尾空白
        assertEquals("4823", codeOf("绑定 4823　"));      // 尾全角空格
    }

    @Test
    void rejectsInvalidForms() {
        assertNull(codeOf("绑定 482"));        // 3 位
        // 5~8 位由正则放行、由码表裁决（不存在即 WRONG_CODE）
        assertEquals("48233", codeOf("绑定 48233"));
        assertNull(codeOf("绑定 123456789"));  // 9 位超出上限
        assertNull(codeOf("绑定 48a3"));
        assertNull(codeOf("帮我绑定 4823"));
        assertNull(codeOf("绑定 4823 谢谢"));
        assertNull(codeOf("查询 4823"));
        assertNull(codeOf(""));
        assertNull(codeOf("1234"));
    }

    @Test
    void cqCodeStrippedBeforeMatch() {
        Pattern cq = Pattern.compile("\\[CQ:[^\\]]*]");
        String raw = "[CQ:at,qq=10001] 绑定 4823";
        String text = cq.matcher(raw).replaceAll("").trim();
        assertEquals("4823", codeOf(text));
    }

    @Test
    void unbindForms() {
        assertTrue(UNBIND.matcher("解绑").matches());
        assertTrue(UNBIND.matcher(" 解绑 ").matches());
        assertFalse(UNBIND.matcher("解绑 Steve").matches());
        assertFalse(UNBIND.matcher("绑定 1234").matches());
    }
    @Test
    void patternsMatchImplementation() throws Exception {
        // 直接从实现类反射读取正则并验证行为（无本地副本，永不过期）
        Class<?> c = ChatMessageHandler.class;
        var f1 = c.getDeclaredField("BIND");
        f1.setAccessible(true);
        var f2 = c.getDeclaredField("UNBIND");
        f2.setAccessible(true);
        @SuppressWarnings("unchecked")
        var bindImpl = (Pattern) f1.get(null);
        @SuppressWarnings("unchecked")
        var unbindImpl = (Pattern) f2.get(null);
        assertTrue(bindImpl.matcher("绑定 4823").matches());
        assertFalse(bindImpl.matcher("绑定 Steve 12345678").matches());
        assertTrue(unbindImpl.matcher("解绑").matches());
        assertFalse(unbindImpl.matcher("解绑x").matches());
    }

    // ---------------- v4 新指令形态 ----------------

    @Test
    void unbindWithAccountName() {
        Matcher m = ChatMessageHandler.UNBIND.matcher("解绑 Steve");
        assertTrue(m.matches());
        assertEquals("Steve", m.group(1));
        assertNull(m.group(2));
        assertTrue(ChatMessageHandler.UNBIND.matcher("解绑").matches());
        assertTrue(ChatMessageHandler.UNBIND.matcher(" 解绑 ").matches());
        // 尾参 5~12 位数字 → 管理员精确解绑形态，同样命中（分流在 handler 层）
        Matcher m2 = ChatMessageHandler.UNBIND.matcher("解绑 Steve 12345");
        assertTrue(m2.matches());
    }


    @Test
    void adminPatterns() {
        Matcher m = ChatMessageHandler.ADMIN_BIND.matcher("绑定 Steve 10086");
        assertTrue(m.matches());
        assertEquals("Steve", m.group(1));
        assertEquals("10086", m.group(2));

        m = ChatMessageHandler.ADMIN_BIND.matcher("绑定 4823");
        assertFalse(m.matches()); // 纯码是玩家绑定

        m = ChatMessageHandler.UNBIND_ALL.matcher("全解绑 Steve");
        assertTrue(m.matches());
        assertEquals("Steve", m.group(1));
        assertFalse(ChatMessageHandler.UNBIND_ALL.matcher("全解绑").matches());

        m = ChatMessageHandler.ADMIN_LOOKUP.matcher("查 1122334455");
        assertTrue(m.matches());
        assertFalse(ChatMessageHandler.ADMIN_LOOKUP.matcher("查询").matches());

        assertTrue(ChatMessageHandler.QUERY.matcher("查询").matches());
        assertTrue(ChatMessageHandler.HELP.matcher("帮助").matches());
        assertTrue(ChatMessageHandler.STATUS.matcher("状态").matches());
    }

    @Test
    void unbindWithExactQqForm() {
        Matcher m = ChatMessageHandler.UNBIND.matcher("解绑 Steve 10086");
        assertTrue(m.matches());
        assertEquals("Steve", m.group(1));
        assertEquals("10086", m.group(2));

        m = ChatMessageHandler.UNBIND.matcher("解绑 1122334455");
        assertTrue(m.matches());
        assertEquals("1122334455", m.group(1)); // 纯数字目标（QQ形态）
    }
}
