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
    private final BooleanSupplier prettyPrint;
    private final List<Binding> bindings = new ArrayList<>();
    private final Object ioLock = new Object();

    public BindStore(Path dataFolder, BooleanSupplier prettyPrint) {
        this.file = dataFolder.resolve("bindings.json");
        this.prettyPrint = prettyPrint;
    }

    /** 从磁盘加载（覆盖内存态）。 */
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
        }
    }

    /** 异步/同步落盘由调用方决定调度。 */
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
}
