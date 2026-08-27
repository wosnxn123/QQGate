package dev.qqgate.onebot;

import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindSettings;
import dev.qqgate.bind.BindStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OneBot 端到端模拟：FakeBot（WS 客户端扮演 NapCat）反连内嵌 reverse-ws 服务端，
 * 推送群消息事件，断言 bind 裁决与 send_group_msg 回帧。
 */
class OneBotE2ETest {

    private static final String TOKEN = "test-token";

    static final class MapConfig implements dev.qqgate.BotConfig {
        private final Map<String, Object> map;

        MapConfig(Map<String, Object> map) {
            this.map = map;
        }

        @Override
        public String configString(String path, String def) {
            Object v = map.get(path);
            return v == null ? def : String.valueOf(v);
        }

        @Override
        public int configInt(String path, int def) {
            Object v = map.get(path);
            return v instanceof Number n ? n.intValue() : def;
        }

        @Override
        public boolean configBool(String path, boolean def) {
            Object v = map.get(path);
            return v instanceof Boolean b ? b : def;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> configStringList(String path) {
            Object v = map.get(path);
            return v instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    /** 扮演 OneBot 实现（NapCat）的 WS 客户端。 */
    static final class FakeBot extends WebSocketClient {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> closed = new LinkedBlockingQueue<>();

        FakeBot(URI uri, String token) {
            super(uri);
            if (token != null) {
                addHeader("Authorization", "Bearer " + token);
                addHeader("X-Self-ID", "999");
                addHeader("X-Client-Role", "universal");
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            received.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed.add(true);
        }

        @Override
        public void onError(Exception ex) {
        }

        String awaitMessage(long seconds) throws InterruptedException {
            String m = received.poll(seconds, TimeUnit.SECONDS);
            assertNotNull(m, "expected a frame from endpoint within " + seconds + "s");
            return m;
        }
    }

    @TempDir
    Path dir;

    private BindStore store;
    private BindService svc;
    private OneBotEndpoint endpoint;
    private FakeBot bot;
    private final java.util.Map<String, Object> cfg = new java.util.concurrent.ConcurrentHashMap<>(
            java.util.Map.of(
                    "onebot.mode", "reverse-ws",
                    "onebot.listen-host", "127.0.0.1",
                    "onebot.listen-port", 0,
                    "onebot.access-token", TOKEN,
                    "groups.allowed", List.of("777")));

    @BeforeEach
    void setUp() throws Exception {
        store = new BindStore(dir, () -> false);
        store.load();
        svc = new BindService(store);
        svc.updateSettings(new BindSettings.Builder().cooldownSeconds(0).build());
        MapConfig config = new MapConfig(cfg);
        endpoint = new OneBotEndpoint(config, Logger.getLogger("e2e"));
        endpoint.setMessageHandler(new ChatMessageHandler(config, svc, endpoint, Logger.getLogger("e2e"),
                new MsgRenderer(config), new dev.qqgate.admin.AdminOps(svc)));
        endpoint.start();

        int port = awaitPort();
        bot = new FakeBot(URI.create("ws://127.0.0.1:" + port + "/"), TOKEN);
        assertTrue(bot.connectBlocking(5, TimeUnit.SECONDS), "fake bot should connect");
        Thread.sleep(200); // 等 onConnectionOpen 完成
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bot != null && bot.isOpen()) bot.closeBlocking();
        if (endpoint != null) endpoint.stop();
    }

    private int awaitPort() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        int port = endpoint.reversePort();
        while (port <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            port = endpoint.reversePort();
        }
        assertTrue(port > 0, "reverse server should bind a port");
        return port;
    }

    private static String groupEvent(long group, long user, String raw) {
        return "{\"time\":1,\"self_id\":999,\"post_type\":\"message\",\"message_type\":\"group\","
                + "\"sub_type\":\"normal\",\"message_id\":1,\"group_id\":" + group
                + ",\"user_id\":" + user + ",\"raw_message\":\"" + raw + "\"}";
    }

    /** 数组格式 message 段（无 raw_message）。 */
    private static String groupEventArray(long group, long user, String text) {
        return "{\"time\":1,\"self_id\":999,\"post_type\":\"message\",\"message_type\":\"group\","
                + "\"group_id\":" + group + ",\"user_id\":" + user
                + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"" + text + "\"}}]}";
    }

    @Test
    void fullBindFlowWithSpace() throws Exception {
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());

        bot.send(groupEvent(777, 10001, "绑定 " + code.code()));
        String reply = bot.awaitMessage(5);

        assertTrue(reply.contains("\"action\":\"send_group_msg\""), reply);
        assertTrue(reply.contains("\"group_id\":777"), reply);
        assertTrue(reply.contains("[CQ:reply,id=1]"), reply);   // 引用触发消息
        assertTrue(reply.contains("绑定成功"), reply);
        assertTrue(reply.contains("[CQ:at,qq=10001]"), reply);
        assertTrue(reply.contains("Steve"), reply);
        assertTrue(reply.contains("1/1"), reply);               // 已绑定计数
        assertTrue(reply.contains("还可绑定 0"), reply);          // 剩余额度
        assertTrue(store.isBound(u));
        assertEquals(1, store.findByQq(10001).size());
        // 码已消费
        assertEquals(0, svc.activeCodeCount());
    }

    @Test
    void bindWithoutSpaceAndArrayMessage() throws Exception {
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Alex", System.currentTimeMillis());

        bot.send(groupEventArray(777, 10002, "绑定" + code.code()));
        String reply = bot.awaitMessage(5);
        assertTrue(reply.contains("绑定成功"), reply);
        assertTrue(reply.contains("Alex"), reply);
        assertTrue(store.isBound(u));
    }

    @Test
    void wrongCodeRepliedWithFailure() throws Exception {
        svc.ensureCode(UUID.randomUUID(), "Steve", System.currentTimeMillis());
        bot.send(groupEvent(777, 10003, "绑定 9999"));
        String reply = bot.awaitMessage(5);
        assertTrue(reply.contains("验证码错误"), reply);
    }

    @Test
    void foreignGroupIgnored() throws Exception {
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());
        bot.send(groupEvent(888, 10004, "绑定 " + code.code()));
        assertNull(bot.received.poll(1, TimeUnit.SECONDS), "non-whitelisted group must be ignored");
        assertFalse(store.isBound(u));
    }

    @Test
    void badTokenRejected() throws Exception {
        int port = endpoint.reversePort();
        FakeBot intruder = new FakeBot(URI.create("ws://127.0.0.1:" + port + "/"), "wrong-token");

        // 握手阶段 401：连接建立失败（而非连上后被关闭）
        assertFalse(intruder.connectBlocking(15, TimeUnit.SECONDS),
                "unauthorized handshake must be rejected");
        // 原连接不受影响
        assertTrue(endpoint.status().connected());
    }

    /** 私聊事件（message_type=private, sub_type=friend）。 */
    private static String privateEvent(long user, String raw) {
        return "{\"time\":1,\"self_id\":999,\"post_type\":\"message\",\"message_type\":\"private\","
                + "\"sub_type\":\"friend\",\"message_id\":9,\"user_id\":" + user
                + ",\"raw_message\":\"" + raw + "\"}";
    }

    @Test
    void privateBindAllowedWhenEnabled() throws Exception {
        cfg.put("private.allow-bind", true);
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());

        bot.send(privateEvent(20001, "绑定 " + code.code()));
        String reply = bot.awaitMessage(5);

        assertTrue(reply.contains("\"action\":\"send_private_msg\""), reply);
        assertTrue(reply.contains("\"user_id\":20001"), reply);
        assertTrue(reply.contains("绑定成功"), reply);
        assertFalse(reply.contains("[CQ:at"), reply); // 私聊无艾特
        assertTrue(store.isBound(u));
    }

    @Test
    void privateBindIgnoredByDefault() throws Exception {
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());

        bot.send(privateEvent(20001, "绑定 " + code.code()));
        assertNull(bot.received.poll(1, TimeUnit.SECONDS), "private bind must be ignored when disabled");
        assertFalse(store.isBound(u));
    }

    // ================= 管理员指令 E2E =================

    private static String groupEventAdmin(long group, long user, String raw) {
        return "{\"time\":1,\"self_id\":999,\"post_type\":\"message\",\"message_type\":\"group\","
                + "\"sub_type\":\"normal\",\"message_id\":77,\"group_id\":" + group
                + ",\"user_id\":" + user + ",\"raw_message\":\"" + raw + "\"}";
    }

    @Test
    void adminLookupWorksForWhitelistedQq() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());
        svc.attemptBind(code.code(), 10001L, System.currentTimeMillis() + 100);

        bot.send(groupEventAdmin(777, 90001, "查 Steve"));
        String reply = bot.awaitMessage(5);
        assertTrue(reply.contains("Steve"), reply);
        assertTrue(reply.contains("10001"), reply);
    }

    @Test
    void adminCommandsIgnoredForNonAdmin() throws Exception {
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());
        svc.attemptBind(code.code(), 10001L, System.currentTimeMillis() + 100);

        bot.send(groupEventAdmin(777, 88888, "查 Steve")); // 非白名单
        assertNull(bot.received.poll(1, TimeUnit.SECONDS), "admin cmd must be ignored for non-admin");
        assertTrue(store.isBound(u)); // 数据未动
    }

    @Test
    void adminUnbindSingleAndAll() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        svc.updateSettings(new BindSettings.Builder().maxPerQq(2).cooldownSeconds(0).build());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var ca = svc.ensureCode(a, "Steve", System.currentTimeMillis());
        svc.attemptBind(ca.code(), 10001L, System.currentTimeMillis() + 100);
        var cb = svc.ensureCode(b, "Alex", System.currentTimeMillis());
        svc.attemptBind(cb.code(), 10001L, System.currentTimeMillis() + 200);

        // 单参 QQ 形态多条 → 列表
        bot.send(groupEventAdmin(777, 90001, "解绑 10001"));
        String reply = bot.awaitMessage(5);
        assertTrue(reply.contains("2 条绑定"), reply);
        assertTrue(reply.contains("Steve"), reply);
        assertTrue(store.isBound(a)); // 未执行

        // 精确解绑
        bot.send(groupEventAdmin(777, 90001, "解绑 Steve 10001"));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("已解绑"), reply);
        assertFalse(store.isBound(a));
        assertTrue(store.isBound(b));

        // 全解绑
        bot.send(groupEventAdmin(777, 90001, "全解绑 10001"));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("已清空"), reply);
        assertFalse(store.isBound(b));
    }

    @Test
    void adminUnbindAllIgnoresChannelWhenDisabled() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        cfg.put("admins.respond.group", false); // 群内管理通道关闭
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());
        svc.attemptBind(code.code(), 10001L, System.currentTimeMillis() + 100);

        bot.send(groupEventAdmin(777, 90001, "全解绑 10001"));
        assertNull(bot.received.poll(1, TimeUnit.SECONDS), "admin cmd must respect channel switch");
        assertTrue(store.isBound(u));
    }

    @Test
    void adminHelpShowsBothSections() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        bot.send(groupEventAdmin(777, 90001, "帮助"));
        String reply = bot.awaitMessage(5);
        // 合并显示：玩家段 + 管理员段都在，且只有一处 @
        assertTrue(reply.contains("绑定 <验证码>"), reply);
        assertTrue(reply.contains("查询"), reply);
        assertTrue(reply.contains("管理员指令"), reply);
        assertTrue(reply.contains("全解绑"), reply);
        assertEquals(1, reply.split("\\[CQ:at,qq=90001\\]", -1).length - 1, "exactly one @");
        // 普通玩家帮助只含玩家段
        bot.send(groupEventAdmin(777, 10001, "帮助"));
        String playerReply = bot.awaitMessage(5);
        assertFalse(playerReply.contains("管理员指令"), playerReply);
    }

    @Test
    void helpReflectsSelfUnbindSwitch() throws Exception {
        // 默认关：帮助不含解绑指令行，提示未开启
        bot.send(groupEventAdmin(777, 10001, "帮助"));
        String off = bot.awaitMessage(5);
        assertFalse(off.contains("解绑 <账号名>"), off);
        assertTrue(off.contains("自助解绑未开启"), off);

        // 开启后：帮助出现解绑指令行
        svc.updateSettings(new BindSettings.Builder().selfUnbind(true).build());
        bot.send(groupEventAdmin(777, 10001, "帮助"));
        String on = bot.awaitMessage(5);
        assertTrue(on.contains("解绑 <账号名>"), on);
        assertFalse(on.contains("自助解绑未开启"), on);
    }

    @Test
    void adminQqBanFlow() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());
        svc.attemptBind(code.code(), 10001L, System.currentTimeMillis() + 100);
        assertTrue(store.isBound(u));

        // 拉黑：绑定保留 + 名单回执
        bot.send(groupEventAdmin(777, 90001, "拉黑 10001 外挂"));
        String reply = bot.awaitMessage(5);
        assertTrue(reply.contains("已拉黑"), reply);
        assertTrue(reply.contains("Steve"), reply);      // 名下账号名字展示
        assertTrue(store.isBound(u));                     // 绑定未删（案底保留）

        // 被拉黑 QQ 重绑被拒（新账号+干净名字）
        UUID u2 = UUID.randomUUID();
        var c2 = svc.ensureCode(u2, "New", System.currentTimeMillis());
        bot.send(groupEventAdmin(777, 10001, "绑定 " + c2.code()));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("拉黑"), reply);

        // Steve 本人换干净QQ也被拒（名下拉黑QQ拦截）
        var c2b = svc.ensureCode(u, "Steve", System.currentTimeMillis() + 50);
        bot.send(groupEventAdmin(777, 10002, "绑定 " + c2b.code()));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("拉黑"), reply);

        // 列表含名字 + 解除
        bot.send(groupEventAdmin(777, 90001, "拉黑列表"));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("10001"), reply);
        assertTrue(reply.contains("Steve"), reply);
        bot.send(groupEventAdmin(777, 90001, "解拉黑 10001"));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("已解除"), reply);
        // 解除后 Steve 绑定复原（案底即占用，无需重绑）
        assertTrue(store.isBound(u));
        // 10001 名额仍被 Steve 占用（maxPerQq=1）→ 新账号绑定被 QQ_FULL 拒绝（案底占位语义）
        var c3 = svc.ensureCode(u2, "New", System.currentTimeMillis());
        bot.send(groupEventAdmin(777, 10001, "绑定 " + c3.code()));
        reply = bot.awaitMessage(5);
        assertTrue(reply.contains("绑定满"), reply);
    }

    @Test
    void cqInjectionNeutralized() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        // 双层防御验证：入站剥离 + sanitize 后，任何回执都不得携带可解析的恶意 CQ 码
        // a) 常规注入：入站剥离正则直接删除（残余"查"无参数→静默或空目标回执）
        bot.send(groupEventAdmin(777, 90001, "查 [CQ:record,file=http://evil/x.mp3]"));
        String reply = bot.received.poll(3, TimeUnit.SECONDS);
        if (reply != null) {
            assertFalse(reply.matches("(?s).*\\[CQ:(record|image|share)[^]]*].*"),
                    "回执不得含可解析恶意码: " + reply);
        }
        // b) 嵌套漏网形态：剥离后残余无害，sanitize 再兜底
        bot.send(groupEventAdmin(777, 90001, "查 [CQ:share,url=http://evil]tricky]"));
        String reply2 = bot.received.poll(3, TimeUnit.SECONDS);
        if (reply2 != null) {
            assertFalse(reply2.matches("(?s).*\\[CQ:share[^]]*].*"), "漏网形态亦不得成码: " + reply2);
        }
    }

    /** 未替换占位符形态：小写字母加下划线包在花括号里（与 QqMsg.Field.token() 同构）。 */
    private static final java.util.regex.Pattern LEFTOVER = java.util.regex.Pattern.compile("\\{[a-z_]+}");

    private void assertNoPlaceholder(String frame, String cmd) {
        assertNotNull(frame, "expected a reply for: " + cmd);
        var m = LEFTOVER.matcher(frame);
        if (m.find()) {
            fail("回执残留未替换占位符 " + m.group() + "（指令「" + cmd + "」）: " + frame);
        }
    }

    /** 发一条指令，扫本轮所有回帧（首帧必到；后续帧短轮询兜底），返回拼接文本供内容断言。 */
    private String sendAndScan(long user, String cmd) throws Exception {
        bot.received.clear();
        bot.send(groupEventAdmin(777, user, cmd));
        String first = bot.received.poll(5, TimeUnit.SECONDS);
        assertNoPlaceholder(first, cmd);
        StringBuilder all = new StringBuilder(first);
        for (String more = bot.received.poll(300, TimeUnit.MILLISECONDS); more != null;
                more = bot.received.poll(300, TimeUnit.MILLISECONDS)) {
            assertNoPlaceholder(more, cmd);
            all.append('\n').append(more);
        }
        return all.toString();
    }

    /**
     * 文案契约回归：QQ 侧任何回执都不得带出未替换的 {xxx}。
     *
     * <p>1.6.0 之前的真实 bug：{@code admin-bind-no-player} 的 {player} 与
     * {@code admin-lookup-empty} 的 {target} 从不替换，用户直接看到裸花括号；
     * 另有五个键把整段结果塞进 {result}。本用例把 QQ 侧主要指令跑一遍，逐帧正则扫描，
     * 任一键漏传字段即失败。
     */
    @Test
    void noLeftoverPlaceholdersAcrossQqCommands() throws Exception {
        cfg.put("admins.qq", List.of("90001"));
        svc.updateSettings(new BindSettings.Builder().cooldownSeconds(0).selfUnbind(true).build());
        UUID u = UUID.randomUUID();
        var code = svc.ensureCode(u, "Steve", System.currentTimeMillis());

        assertTrue(sendAndScan(90001, "帮助").contains("管理员指令"));
        assertTrue(sendAndScan(10001, "绑定 " + code.code()).contains("Steve"));
        assertTrue(sendAndScan(10001, "查询").contains("Steve"));
        sendAndScan(10001, "绑定 " + code.code());              // 码已消费：错误/已用回执
        assertTrue(sendAndScan(10001, "解绑").contains("Steve")); // 自助解绑列表（头+条目+提示）
        assertTrue(sendAndScan(90001, "查 10001").contains("Steve"));
        assertTrue(sendAndScan(90001, "查 Steve").contains("10001"));
        // 回归：空结果分支必须替换 {target}
        assertTrue(sendAndScan(90001, "查 NoSuchPlayer").contains("NoSuchPlayer"));
        // 回归：20 位纯数字不再是数字目标，按玩家名处理且不崩
        assertTrue(sendAndScan(90001, "查 99999999999999999999").contains("99999999999999999999"));
        // 回归：无既有记录的代绑必须替换 {player}
        assertTrue(sendAndScan(90001, "绑定 GhostPlayer 10009").contains("GhostPlayer"));
        assertTrue(sendAndScan(90001, "拉黑 10001 外挂").contains("Steve"));
        assertTrue(sendAndScan(90001, "拉黑列表").contains("10001"));
        sendAndScan(90001, "解拉黑 10001");
        sendAndScan(90001, "解拉黑 10001");                      // 第二次：不在黑名单分支
        assertTrue(sendAndScan(90001, "解绑 Steve 10001").contains("Steve"));
        sendAndScan(90001, "全解绑 10001");                      // 已无绑定：notfound 标签分支
        String status = sendAndScan(90001, "状态");
        assertTrue(status.contains("mode="), status);
        assertTrue(status.contains("binds="), status);
    }
}

