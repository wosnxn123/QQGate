package dev.qqgate.bind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 绑定数据：内存态 + JSON 持久化。纯 Java（无 Bukkit 依赖），可单测。
 *
 * 线程模型：所有变更方法 synchronized；读取方法返回快照拷贝。
 * 绑定量级为服务器人数，锁开销可忽略。
 */
public final class BindStore {

    /** 一条绑定：游戏账号(UUID) ↔ QQ。 */
    public record Binding(UUID uuid, String name, long qq, long boundAt) {
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type LIST_TYPE = new TypeToken<List<Binding>>() {
    }.getType();

    private static final String DEFAULT_FILE = "bindings.json";
    private static final String BAN_FILE = "banned_qqs.json";
    private static final Type BAN_TYPE = new TypeToken<Map<Long, String[]>>() {
    }.getType();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final Path banFile;
    private final BooleanSupplier prettyPrint;
    private final Consumer<String> warn;
    private final List<Binding> bindings = new ArrayList<>();
    /** QQ 黑名单：qq -> (拉黑时间, 原因)。 */
    private final Map<Long, String[]> bannedQqs = new LinkedHashMap<>();
    private final Object ioLock = new Object();

    /** 默认入口：文件名 bindings.json，告警走 stderr。 */
    public BindStore(Path dataFolder, BooleanSupplier prettyPrint) {
        this(dataFolder, prettyPrint, DEFAULT_FILE, null);
    }

    /**
     * @param storageFile storage.file 配置值：相对插件数据目录或绝对路径，非法则回退 bindings.json
     * @param warn        告警信道（插件内接 getLogger()::warning），null 走 stderr
     */
    public BindStore(Path dataFolder, BooleanSupplier prettyPrint, String storageFile, Consumer<String> warn) {
        this.warn = warn != null ? warn : msg -> System.err.println("[QQGate] " + msg);
        this.file = resolveStorage(dataFolder, storageFile, this.warn);
        this.banFile = this.file.resolveSibling(BAN_FILE);
        this.prettyPrint = prettyPrint;
    }

    /** storage.file → 实际路径：空/非法/越出数据目录一律回退默认名。 */
    private static Path resolveStorage(Path dataFolder, String storageFile, Consumer<String> warn) {
        if (storageFile == null || storageFile.isBlank()) {
            return dataFolder.resolve(DEFAULT_FILE);
        }
        try {
            Path candidate = Path.of(storageFile.trim());
            if (candidate.isAbsolute()) {
                return candidate;
            }
            Path resolved = dataFolder.resolve(candidate).normalize();
            if (!resolved.startsWith(dataFolder.normalize())) {
                warn.accept("storage.file 越出插件数据目录（" + storageFile + "），已回退 " + DEFAULT_FILE);
                return dataFolder.resolve(DEFAULT_FILE);
            }
            return resolved;
        } catch (RuntimeException e) {
            warn.accept("storage.file 非法（" + storageFile + "）: " + e + "，已回退 " + DEFAULT_FILE);
            return dataFolder.resolve(DEFAULT_FILE);
        }
    }

    /** 实际使用的绑定文件路径（供 diag 展示）。 */
    public Path file() {
        return file;
    }

    /**
     * 从磁盘加载（覆盖内存态）：绑定 + QQ 黑名单。
     * <p>文件损坏/不可读时把原文件隔离为 {@code .corrupt-<时间戳>} 再以空态启动——
     * 否则下一次 {@link #save()} 会用空数据原子覆盖掉全部绑定（静默数据丢失）。
     */
    public synchronized void load() {
        synchronized (ioLock) {
            List<Binding> loaded = readJson(file, LIST_TYPE, DEFAULT_FILE);
            synchronized (bindings) {
                bindings.clear();
                if (loaded != null) bindings.addAll(loaded);
            }
            Map<Long, String[]> bans = readJson(banFile, BAN_TYPE, BAN_FILE);
            synchronized (bannedQqs) {
                bannedQqs.clear();
                if (bans != null) bannedQqs.putAll(bans);
            }
        }
    }

    /** 读 JSON：不存在或空文件 → null（正常空态）；不可读/损坏 → 隔离原文件后 null。 */
    private <T> T readJson(Path path, Type type, String label) {
        if (!Files.exists(path)) {
            return null;
        }
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            quarantine(path, label, e);
            return null;
        }
        if (json.isBlank()) {
            return null;
        }
        try {
            T parsed = GSON.fromJson(json, type);
            if (parsed == null) {
                throw new IllegalStateException("JSON 顶层内容为 null");
            }
            return parsed;
        } catch (RuntimeException e) {
            quarantine(path, label, e);
            return null;
        }
    }

    /** 坏文件改名保留，让下一次 save() 覆盖不掉它。 */
    private void quarantine(Path path, String label, Exception cause) {
        Path kept = path.resolveSibling(path.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
        try {
            Files.move(path, kept, StandardCopyOption.REPLACE_EXISTING);
            warn.accept(label + " 损坏或不可读（" + cause + "）: 原文件已保留为 " + kept.getFileName()
                    + "，本次以空数据启动；修好后改回原名再 /qqgateadmin reload");
        } catch (IOException io) {
            warn.accept(label + " 损坏或不可读（" + cause + "）且无法隔离（" + io
                    + "）: 本次以空数据启动，请立刻手动备份 " + path + "，下一次保存会覆盖它");
        }
    }

    /** 异步/同步落盘由调用方决定调度。绑定 + QQ 黑名单一起写。 */
    public void save() {
        List<Binding> snapshot;
        synchronized (bindings) {
            snapshot = new ArrayList<>(bindings);
        }
        Map<Long, String[]> banSnapshot;
        synchronized (bannedQqs) {
            banSnapshot = new LinkedHashMap<>(bannedQqs);
        }
        String json = toJson(snapshot);
        String banJson = toJson(banSnapshot);
        synchronized (ioLock) {
            writeAtomic(file, json, DEFAULT_FILE);
            writeAtomic(banFile, banJson, BAN_FILE);
        }
    }

    private String toJson(Object value) {
        return (prettyPrint.getAsBoolean()
                ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                : GSON).toJson(value);
    }

    /** temp + ATOMIC_MOVE；平台不支持原子改名时退普通改名，别让整次保存失败。 */
    private void writeAtomic(Path path, String json, String label) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            warn.accept(label + " 保存失败: " + e);
        }
    }


    public synchronized int countByUuid(UUID uuid) {
        int n = 0;
        for (Binding b : bindings) {
            if (b.uuid().equals(uuid)) n++;
        }
        return n;
    }

    public synchronized int countByQq(long qq) {
        int n = 0;
        for (Binding b : bindings) {
            if (b.qq() == qq) n++;
        }
        return n;
    }

    public synchronized boolean isBound(UUID uuid) {
        return countByUuid(uuid) > 0;
    }

    public synchronized List<Binding> findByUuid(UUID uuid) {
        List<Binding> out = new ArrayList<>();
        for (Binding b : bindings) {
            if (b.uuid().equals(uuid)) out.add(b);
        }
        return out;
    }

    public synchronized List<Binding> findByQq(long qq) {
        List<Binding> out = new ArrayList<>();
        for (Binding b : bindings) {
            if (b.qq() == qq) out.add(b);
        }
        return out;
    }

    public synchronized List<Binding> all() {
        return new ArrayList<>(bindings);
    }

    /** 新增绑定。 */
    public synchronized void add(UUID uuid, String name, long qq, long now) {
        bindings.add(new Binding(uuid, name, qq, now));
    }

    /**
     * 玩家改名后刷新绑定里记的名字。
     * <p>名字封禁（{@link #isNameBanned}）按绑定里的名字比对，不刷新就意味着改名即可绕过封禁。
     *
     * @return 是否真的改动了绑定（调用方据此决定要不要落盘）
     */
    public synchronized boolean refreshName(UUID uuid, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < bindings.size(); i++) {
            Binding b = bindings.get(i);
            if (b.uuid().equals(uuid) && !name.equals(b.name())) {
                bindings.set(i, new Binding(uuid, name, b.qq(), b.boundAt()));
                changed = true;
            }
        }
        return changed;
    }

    /** 挤掉该 QQ 名下最早绑定的账号（用于 limit-policy: replace）。返回被挤掉的绑定。 */
    public synchronized Binding evictOldestOfQq(long qq) {
        Binding oldest = null;
        for (Binding b : bindings) {
            if (b.qq() == qq && (oldest == null || b.boundAt() < oldest.boundAt())) {
                oldest = b;
            }
        }
        if (oldest != null) bindings.remove(oldest);
        return oldest;
    }

    /** 挤掉该玩家名下最早绑定的 QQ。返回被挤掉的绑定。 */
    public synchronized Binding evictOldestOfUuid(UUID uuid) {
        Binding oldest = null;
        for (Binding b : bindings) {
            if (b.uuid().equals(uuid) && (oldest == null || b.boundAt() < oldest.boundAt())) {
                oldest = b;
            }
        }
        if (oldest != null) bindings.remove(oldest);
        return oldest;
    }

    /** 删除玩家全部绑定，返回删除数量。 */
    public synchronized int removeAllByUuid(UUID uuid) {
        int before = bindings.size();
        bindings.removeIf(b -> b.uuid().equals(uuid));
        return before - bindings.size();
    }

    /** 删除某 QQ 与指定玩家的绑定，返回是否删除。 */
    public synchronized boolean removeExact(UUID uuid, long qq) {
        return bindings.removeIf(b -> b.uuid().equals(uuid) && b.qq() == qq);
    }

    public synchronized int size() {
        return bindings.size();
    }

    // ---------------- QQ 黑名单 ----------------

    public synchronized boolean isQqBanned(long qq) {
        return bannedQqs.containsKey(qq);
    }

    /** 玩家名下任一绑定 QQ 被拉黑 → 该玩家视为被拉黑（绑定保留作案底）。 */
    public synchronized boolean isUuidBannedViaQq(UUID uuid) {
        for (Binding b : bindings) {
            if (b.uuid().equals(uuid) && bannedQqs.containsKey(b.qq())) return true;
        }
        return false;
    }

    /** 拉黑 QQ：绑定【保留】作为案底（拦截依赖绑定存在；解拉黑即复原）。
     * 返回该 QQ 名下的玩家名列表（供名字封禁与展示）。幂等：重复拉黑更新原因。 */
    public synchronized java.util.List<String> banQq(long qq, long now, String reason) {
        bannedQqs.put(qq, new String[]{
                String.valueOf(now), reason == null ? "" : reason});
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Binding b : bindings) {
            if (b.qq() == qq && !names.contains(b.name())) names.add(b.name());
        }
        return names;
    }

    /** 解除拉黑：绑定未动，账号即复原可玩。返回是否存在。 */
    public synchronized boolean unbanQq(long qq) {
        return bannedQqs.remove(qq) != null;
    }

    /** QQ 的封禁原因（展示用）；未拉黑返回 null，旧格式缺原因字段返回 ""。 */
    public synchronized String bannedReasonOfQq(long qq) {
        String[] e = bannedQqs.get(qq);
        return e == null ? null : reasonOrEmpty(e);
    }

    /** 玩家名下被拉黑 QQ 的封禁原因（踢出页展示）；无返回 null。 */
    public synchronized String bannedReasonViaUuid(UUID uuid) {
        for (Binding b : bindings) {
            if (b.uuid().equals(uuid)) {
                String[] e = bannedQqs.get(b.qq());
                if (e != null) return reasonOrEmpty(e);
            }
        }
        return null;
    }

    /** 名字封禁命中的封禁原因（踢出页展示）；无返回 null。 */
    public synchronized String bannedReasonViaName(String name) {
        if (name == null) return null;
        for (Binding b : bindings) {
            if (b.qq() != 0 && b.name().equalsIgnoreCase(name)) {
                String[] e = bannedQqs.get(b.qq());
                if (e != null) return reasonOrEmpty(e);
            }
        }
        return null;
    }

    private static String reasonOrEmpty(String[] e) {
        return e == null || e.length < 2 ? "" : e[1];
    }

    /** 名字封禁：任何被拉黑 QQ 名下的玩家名（比对忽略大小写）。 */
    public synchronized boolean isNameBanned(String name) {
        if (name == null) return false;
        for (Binding b : bindings) {
            if (b.qq() != 0 && bannedQqs.containsKey(b.qq())
                    && b.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 黑名单快照（qq -> [时间戳, 原因]）。 */
    public synchronized java.util.Map<Long, String[]> bannedQqs() {
        return new java.util.LinkedHashMap<>(bannedQqs);
    }

    /** 拉黑 QQ 名下的玩家名快照（qqbans 列表展示用）。 */
    public synchronized java.util.List<String> namesOfQq(long qq) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Binding b : bindings) {
            if (b.qq() == qq && !names.contains(b.name())) names.add(b.name());
        }
        return names;
    }
}
