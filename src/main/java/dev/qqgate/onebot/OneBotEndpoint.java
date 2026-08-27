package dev.qqgate.onebot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.qqgate.BotConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * OneBot 11 端点。
 *
 * reverse-ws：内嵌 WebSocketServer，机器人反连（NapCat/LLOneBot 常用，推荐）。
 * forward-ws：WebSocketClient 主动连机器人（Lagrange 常用），断线自动重连。
 *
 * 鉴权：Authorization: Bearer <token> 或 ?access_token=，与机器人端一致。
 * 心跳看门狗：超过 heartbeat-timeout-seconds 无任何事件 → 判定离线。
 *
 * 事件面：群消息 + 私聊消息（private.allow-bind 控制私聊是否放行到处理器）。
 * 仅依赖 BotConfig + Logger（无 Bukkit），可独立单测。
 */
public final class OneBotEndpoint {

    /** 聊天消息事件（群或私聊；messageId 用于引用回复，缺省 0）。 */
    public record IncomingMessage(Scope scope, long groupId, long userId, String rawMessage, long messageId) {
        public enum Scope { GROUP, PRIVATE }

        public boolean isGroup() {
            return scope == Scope.GROUP;
        }
    }

    public interface MessageListener {
        void onMessage(IncomingMessage msg);
    }

    private final BotConfig config;
    private final Logger log;
    private volatile MessageListener listener;
    private volatile boolean running = false;

    private ReverseServer reverseServer;
    private volatile ForwardClient forwardClient;
    private final AtomicReference<WebSocket> activeConn = new AtomicReference<>();
    private volatile long connectedSince = 0;
    private volatile long lastEventAt = 0;
    private volatile long selfId = 0;

    public OneBotEndpoint(BotConfig config, Logger log) {
        this.config = config;
        this.log = log;
    }

    public void setMessageHandler(MessageListener listener) {
        this.listener = listener;
    }

    // ---------------- 生命周期 ----------------

    public void start() {
        running = true;
        if ("forward-ws".equals(config.configString("onebot.mode", "reverse-ws"))) {
            startForward();
        } else {
            startReverse();
        }
    }

    public void stop() {
        running = false;
        if (reverseServer != null) {
            try {
                reverseServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reverseServer = null;
        }
        if (forwardClient != null) {
            forwardClient.close();
            forwardClient = null;
        }
        activeConn.set(null);
        connectedSince = 0;
        // 状态位一并清零：否则重连/重启后 status() 会报上一个机器人的 QQ 号，并按旧事件时间判活
        lastEventAt = 0;
        selfId = 0;
    }

    private void startReverse() {
        String host = config.configString("onebot.listen-host", "0.0.0.0");
        int port = config.configInt("onebot.listen-port", 6700);
        if (!reverseBindPermitted(host, port)) {
            running = false; // 不启动服务端：status() 仍可安全调用，stop() 幂等，插件不崩
            return;
        }
        reverseServer = new ReverseServer(new InetSocketAddress(host, port));
        // ping/pong 探测周期（秒）：机器人静默死亡（TCP 半开）时 java-websocket 约 1.5 个周期内
        // 判定连接丢失并关闭，否则 sendFrame 会一直往死 socket 写。取心跳超时的一半，非法值兜 30。
        int hb = config.configInt("onebot.heartbeat-timeout-seconds", 60);
        reverseServer.setConnectionLostTimeout(hb > 0 ? Math.max(1, hb / 2) : 30);
        reverseServer.start();
        log.info("OneBot reverse-ws listening on " + host + ":" + port);
    }

    /**
     * 空 access-token + 非回环监听 = 任何能连到该端口的人都能伪造管理员事件（远程解绑/拉黑/代绑）。
     * 默认拒启监听；onebot.allow-insecure-bind=true 时降级为警告并照旧启动（可信内网）。
     */
    private boolean reverseBindPermitted(String host, int port) {
        if (!config.configString("onebot.access-token", "").isEmpty() || isLoopbackHost(host)) {
            return true;
        }
        if (config.configBool("onebot.allow-insecure-bind", false)) {
            log.warning("OneBot 未鉴权监听 " + host + ":" + port
                    + "（onebot.allow-insecure-bind=true 已放行）：任何能连到此端口的人都能伪造管理员事件，"
                    + "请确认该端口仅在可信内网可达。");
            return true;
        }
        log.severe("OneBot 反向监听未启动：onebot.access-token 为空，而 onebot.listen-host=" + host
                + " 不是回环地址。");
        log.severe("风险：任何能连到 " + host + ":" + port
                + " 的人都能伪造管理员事件——远程解绑玩家、拉黑 QQ、给任意 QQ 代绑账号。");
        log.severe("二选一修复：");
        log.severe("  1) 把 onebot.access-token 设为一个随机长口令（机器人端 NapCat/LLOneBot 填同一个）；");
        log.severe("  2) 或把 onebot.listen-host 改回 127.0.0.1（机器人与服务端同机时的推荐值）。");
        log.severe("确实要在可信内网无鉴权运行：把 onebot.allow-insecure-bind 设为 true（默认 false）。");
        return false;
    }

    /** 只认字面量回环地址，避免在启动线程上做 DNS 解析。 */
    private static boolean isLoopbackHost(String host) {
        String h = host == null ? "" : host.trim();
        if (h.length() > 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            h = h.substring(1, h.length() - 1); // [::1] 写法
        }
        return h.startsWith("127.") || "::1".equals(h) || "localhost".equalsIgnoreCase(h);
    }

    /** reverse-ws 实际绑定端口（listen-port=0 时用于测试发现端口）。 */
    public int reversePort() {
        return reverseServer == null ? -1 : reverseServer.getPort();
    }

    private void startForward() {
        ForwardClient c = new ForwardClient(URI.create(config.configString("onebot.forward-url",
                "ws://127.0.0.1:3001")));
        forwardClient = c;
        c.connect();
    }

    // ---------------- 发送 ----------------

    /** 发群消息（文本，支持 CQ 码如 [CQ:at,qq=xxx]）；false = 未连接或写入失败。 */
    public boolean sendGroupMessage(long groupId, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        return sendFrame("send_group_msg", params, "group " + groupId, text);
    }

    /** 发私聊消息；false = 未连接或写入失败。 */
    public boolean sendPrivateMessage(long userId, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("user_id", userId);
        return sendFrame("send_private_msg", params, "user " + userId, text);
    }

    private boolean sendFrame(String action, JsonObject params, String target, String text) {
        WebSocket conn = activeConn.get();
        if (conn == null || !conn.isOpen()) {
            log.warning("OneBot not connected, drop message to " + target);
            return false;
        }
        // auto_escape 不设（默认 false）：消息内的 CQ 码（reply/at）需被 OneBot 端解析渲染。
        // 入站方向的用户昵称安全由 ChatMessageHandler 的 CQ 剥离正则保障。
        params.addProperty("message", text);
        JsonObject frame = new JsonObject();
        frame.addProperty("action", action);
        frame.add("params", params);
        frame.addProperty("echo", UUID.randomUUID().toString());
        String json = frame.toString();
        if (config.configBool("debug", false)) {
            log.info("[OneBot ->] " + json);
        }
        try {
            conn.send(json);
        } catch (RuntimeException e) {
            // isOpen() 与 send 之间连接可能已被关闭：WebsocketNotConnectedException 不能逃到调用方，
            // 否则会走 onError 且回复静默丢失。
            log.warning("OneBot send failed to " + target + ": " + e);
            return false;
        }
        return true;
    }

    // ---------------- 事件处理（WS 线程）----------------

    private void handleEvent(WebSocket conn, String message) {
        if (config.configBool("debug", false)) {
            log.info("[OneBot <-] " + message);
        }
        JsonObject event;
        try {
            event = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            return; // 非 JSON 对象帧
        }
        if (!event.has("post_type")) {
            logApiError(event); // API 响应（echo 回包）：只有 retcode != 0 才值得上报
            return;
        }
        lastEventAt = System.currentTimeMillis();
        long sid = num(event, "self_id", 0);
        if (sid != 0) {
            selfId = sid;
        }

        long allowed = config.configInt("onebot.allowed-self-id", 0);
        if (allowed > 0 && selfId != 0 && selfId != allowed) {
            return; // 非信任机器人的事件
        }

        String postType = str(event, "post_type");
        String messageType = str(event, "message_type");
        if ("message".equals(postType) && ("group".equals(messageType) || "private".equals(messageType))) {
            dispatchChat(event, "group".equals(messageType));
        }
        // meta_event lifecycle/heartbeat 与 notice 不处理，仅刷新 lastEventAt
    }

    /** echo 回包：retcode != 0 说明发消息被机器人端拒了（权限/被禁言/参数错），否则静默丢弃。 */
    private void logApiError(JsonObject resp) {
        JsonElement rc = resp.get("retcode");
        if (rc == null || !rc.isJsonPrimitive()) {
            return;
        }
        long code;
        try {
            code = rc.getAsLong();
        } catch (NumberFormatException e) {
            return;
        }
        if (code == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder("OneBot API 调用失败: retcode=").append(code);
        String status = str(resp, "status");
        if (status != null) {
            sb.append(" status=").append(status);
        }
        String wording = str(resp, "wording");
        if (wording == null) {
            wording = str(resp, "message");
        }
        if (wording != null && !wording.isEmpty()) {
            sb.append(" msg=").append(wording);
        }
        log.warning(sb.toString());
    }

    private void dispatchChat(JsonObject event, boolean isGroup) {
        String raw = str(event, "raw_message");
        if (raw == null) {
            raw = flattenMessageArray(event.get("message"));
        }
        MessageListener l = listener;
        if (l == null || raw == null) {
            return;
        }
        long userId = num(event, "user_id", 0);
        if (userId == 0) {
            return; // user_id 缺失/非数字：无法归属，丢弃
        }
        long groupId = isGroup ? num(event, "group_id", 0) : 0;
        l.onMessage(new IncomingMessage(
                isGroup ? IncomingMessage.Scope.GROUP : IncomingMessage.Scope.PRIVATE,
                groupId, userId, raw, num(event, "message_id", 0)));
    }

    /** 数字取值守卫：非 primitive（object/array）或非数字一律返回 def，绝不抛到 onError。 */
    private static long num(JsonObject o, String key, long def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        // 非 primitive 调 getAsString() 会抛 UnsupportedOperationException：上游只要发
        // {"post_type":{}} 就能把异常推到 onError，这里一律当缺省值。
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    /** message 为数组格式时拼出纯文本（@等非文本段丢弃）。 */
    private static String flattenMessageArray(JsonElement message) {
        if (message == null || !message.isJsonArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement seg : message.getAsJsonArray()) {
            if (!seg.isJsonObject()) continue;
            JsonObject o = seg.getAsJsonObject();
            if ("text".equals(str(o, "type"))) {
                JsonElement d = o.get("data");
                if (d != null && d.isJsonObject()) {
                    String t = str(d.getAsJsonObject(), "text");
                    if (t != null) sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    // ---------------- 鉴权 ----------------

    private boolean authorized(String authHeader, String query) {
        String token = config.configString("onebot.access-token", "");
        if (token.isEmpty()) return true;
        if (authHeader != null && ("Bearer " + token).equals(authHeader)) return true;
        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "access_token".equals(kv.substring(0, eq))
                        && token.equals(kv.substring(eq + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onConnectionOpen(WebSocket conn, long selfIdFromHeader) {
        WebSocket prev = activeConn.getAndSet(conn);
        if (prev != null && prev != conn && prev.isOpen()) {
            prev.close(); // 单连接模型：新连接顶替旧连接
        }
        connectedSince = System.currentTimeMillis();
        lastEventAt = connectedSince; // 首个事件到达前不拿上一次连接的时间判定心跳过期
        if (selfIdFromHeader > 0) selfId = selfIdFromHeader;
        log.info("OneBot connected" + (selfIdFromHeader > 0 ? " (self_id=" + selfIdFromHeader + ")" : ""));
    }

    // ---------------- 状态 ----------------

    public record Status(String mode, boolean connected, long selfId, long connectedSeconds,
                         long lastEventSecondsAgo) {
    }

    public Status status() {
        String mode = config.configString("onebot.mode", "reverse-ws");
        WebSocket conn = activeConn.get();
        boolean connected = conn != null && conn.isOpen();
        long timeoutMs = config.configInt("onebot.heartbeat-timeout-seconds", 60) * 1000L;
        boolean heartbeatStale = connected && lastEventAt > 0
                && System.currentTimeMillis() - lastEventAt > timeoutMs;
        return new Status(mode, connected && !heartbeatStale, selfId,
                connectedSince == 0 ? 0 : (System.currentTimeMillis() - connectedSince) / 1000,
                lastEventAt == 0 ? -1 : (System.currentTimeMillis() - lastEventAt) / 1000);
    }

    // ================= 反向 WS 服务端 =================

    private final class ReverseServer extends WebSocketServer {

        private ReverseServer(InetSocketAddress addr) {
            super(addr);
        }

        /** 握手阶段拒绝（不进入 onOpen，客户端立即收到失败握手——比 onOpen 后 close 更可靠）。 */
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn,
                Draft draft, ClientHandshake request) throws InvalidDataException {
            String auth = request.getFieldValue("Authorization");
            String resource = conn.getResourceDescriptor();
            int q = resource == null ? -1 : resource.indexOf('?');
            if (!authorized(auth, q >= 0 ? resource.substring(q + 1) : null)) {
                log.warning("OneBot connection rejected: bad access token from "
                        + conn.getRemoteSocketAddress());
                throw new InvalidDataException(401);
            }
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            long sid = 0;
            try {
                String s = handshake.getFieldValue("X-Self-ID");
                if (!s.isEmpty()) sid = Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
            onConnectionOpen(conn, sid);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            if (activeConn.compareAndSet(conn, null)) {
                connectedSince = 0;
                log.info("OneBot disconnected (code=" + code + ")");
            }
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            handleEvent(conn, message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            log.warning("OneBot ws error: " + ex);
        }

        @Override
        public void onStart() {
        }
    }

    // ================= 正向 WS 客户端 =================

    private final class ForwardClient extends WebSocketClient {

        private ForwardClient(URI uri) {
            super(uri);
            String token = config.configString("onebot.access-token", "");
            if (!token.isEmpty()) {
                addHeader("Authorization", "Bearer " + token);
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            activeConn.set(this);
            connectedSince = System.currentTimeMillis();
            lastEventAt = connectedSince; // 同 onConnectionOpen：避免首个事件前误报离线
            log.info("OneBot forward-ws connected to " + getURI());
        }

        @Override
        public void onMessage(String message) {
            handleEvent(this, message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            activeConn.compareAndSet(this, null);
            connectedSince = 0;
            if (!running) return; // 主动 stop 不重连
            long delay = Math.max(1, config.configInt("onebot.reconnect-seconds", 5));
            log.warning("OneBot forward-ws closed (code=" + code + "), reconnect in " + delay + "s");
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running) startForward();
        }

        @Override
        public void onError(Exception ex) {
            log.warning("OneBot forward-ws error: " + ex);
        }
    }
}
