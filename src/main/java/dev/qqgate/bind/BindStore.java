package dev.qqgate.bind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

    private final Path file;
    private final Path banFile;
    private final BooleanSupplier prettyPrint;
    private final List<Binding> bindings = new ArrayList<>();
    /** QQ 黑名单：qq -> (拉黑时间, 原因)。 */
    private final java.util.Map<Long, String[]> bannedQqs = new java.util.LinkedHashMap<>();
    private final Object ioLock = new Object();

    public BindStore(Path dataFolder, BooleanSupplier prettyPrint) {
        this.file = dataFolder.resolve("bindings.json");
        this.banFile = dataFolder.resolve("banned_qqs.json");
        this.prettyPrint = prettyPrint;
    }

    /** 从磁盘加载（覆盖内存态）：绑定 + QQ 黑名单。 */
    public synchronized void load() {
        synchronized (ioLock) {
            List<Binding> loaded;
            try {
                if (Files.exists(file)) {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    loaded = GSON.fromJson(json, LIST_TYPE);
                    if (loaded == null) loaded = new ArrayList<>();
                } else {
                    loaded = new ArrayList<>();
                }
            } catch (Exception e) {
                System.err.println("[QQGate] Failed to read bindings.json, starting empty: " + e);
                loaded = new ArrayList<>();
            }
            synchronized (bindings) {
                bindings.clear();
                bindings.addAll(loaded);
            }
            java.util.Map<Long, String[]> bans;
            try {
                if (Files.exists(banFile)) {
                    String json = Files.readString(banFile, StandardCharsets.UTF_8);
                    java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.Map<Long, String[]>>() {
                    }.getType();
                    bans = GSON.fromJson(json, t);
                    if (bans == null) bans = new java.util.LinkedHashMap<>();
                } else {
                    bans = new java.util.LinkedHashMap<>();
                }
            } catch (Exception e) {
                System.err.println("[QQGate] Failed to read banned_qqs.json, starting empty: " + e);
                bans = new java.util.LinkedHashMap<>();
            }
            synchronized (bannedQqs) {
                bannedQqs.clear();
                bannedQqs.putAll(bans);
            }
        }
    }
    /** 异步/同步落盘由调用方决定调度。绑定 + QQ 黑名单一起写。 */
    public void save() {
        List<Binding> snapshot;
        synchronized (bindings) {
            snapshot = new ArrayList<>(bindings);
        }
        String json;
        if (prettyPrint.getAsBoolean()) {
            json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(snapshot);
        } else {
            json = GSON.toJson(snapshot);
        }
        synchronized (ioLock) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[QQGate] Failed to save bindings.json: " + e);
            }
        }
        // 黑名单落盘（String[] = [时间戳, 原因]）
        java.util.Map<Long, String[]> banSnapshot;
        synchronized (bannedQqs) {
            banSnapshot = new java.util.LinkedHashMap<>(bannedQqs);
        }
        String banJson = (prettyPrint.getAsBoolean()
                ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                : GSON).toJson(banSnapshot);
        synchronized (ioLock) {
            try {
                Files.createDirectories(banFile.getParent());
                Path tmp = banFile.resolveSibling(banFile.getFileName() + ".tmp");
                Files.writeString(tmp, banJson, StandardCharsets.UTF_8);
                Files.move(tmp, banFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[QQGate] Failed to save banned_qqs.json: " + e);
            }
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
