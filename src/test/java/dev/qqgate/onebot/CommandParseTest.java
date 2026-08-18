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
        // 保证测试正则与实现同步：从实现类反射读取并比对源字符串
        Class<?> c = ChatMessageHandler.class;
        var field = c.getDeclaredField("BIND");
        field.setAccessible(true);
        Pattern impl = (Pattern) field.get(null);
        assertEquals(BIND.pattern(), impl.pattern());
        var field2 = c.getDeclaredField("UNBIND");
        field2.setAccessible(true);
        assertEquals(UNBIND.pattern(), ((Pattern) field2.get(null)).pattern());
    }
}
