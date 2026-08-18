package dev.qqgate.bind;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 绑定业务核心：验证码生命周期 + 绑定裁决。纯 Java（无 Bukkit），可单测。
 *
 * 线程模型：验证码表为 ConcurrentHashMap；绑定裁决在单锁内完成
 * （码表校验 → 限额裁决 → 落库），锁序恒为 serviceLock → store 内部锁。
 */
public final class BindService {

    /** 活跃验证码。 */
    public record PendingCode(String code, UUID uuid, String name, long createdAt, long expiresAt) {
        public boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    /** 绑定尝试结果。 */
    public enum Outcome {
        SUCCESS, WRONG_CODE, CODE_USED, QQ_FULL, PLAYER_FULL,
        SUCCESS_REPLACED, COOLDOWN, ALREADY_BOUND, QQ_BANNED
    }

    public record BindResult(Outcome outcome, BindStore.Binding created,
                             BindStore.Binding evicted, long retryAfterSeconds) {
        public static BindResult simple(Outcome o) {
            return new BindResult(o, null, null, 0);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BindStore store;
    private final Object serviceLock = new Object();
    /** code -> PendingCode（活跃，未消费）。 */
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    /** uuid -> code（活跃码反查，用于重进服刷新作废）。 */
    private final Map<UUID, String> codeByUuid = new ConcurrentHashMap<>();
    /** code -> tombstone 到期时间（刚被消费的码，用于区分“已使用”与“错误”）。 */
    private final Map<String, Long> tombstones = new ConcurrentHashMap<>();
    /** qq -> 上次指令时间戳（冷却）。 */
    private final Map<Long, Long> lastAttempt = new ConcurrentHashMap<>();

    private volatile BindSettings settings = BindSettings.defaults();

    public BindService(BindStore store) {
        this.store = store;
    }

    public void updateSettings(BindSettings s) {
        this.settings = s;
    }

    public BindSettings settings() {
        return settings;
    }

    // ---------------- 验证码 ----------------

    /**
     * 进服时获取（或沿用）该账号的验证码。
     * refresh-on-rejoin 开启时作废旧码生成新码；关闭时未过期旧码沿用。
     */
    public PendingCode ensureCode(UUID uuid, String name, long now) {
        BindSettings s = settings;
        synchronized (serviceLock) {
            String existingCode = codeByUuid.get(uuid);
            if (existingCode != null) {
                PendingCode rec = codes.get(existingCode);
                if (rec != null && !rec.expired(now) && !s.refreshOnRejoin) {
                    return rec; // 沿用未过期旧码
                }
                if (rec != null || tombstones.containsKey(existingCode)) {
                    codes.remove(existingCode);
                    tombstones.remove(existingCode);
                }
            }
            PendingCode created = new PendingCode(uniqueCode(now), uuid, name, now, now + s.expireMillis);
            codes.put(created.code(), created);
            codeByUuid.put(uuid, created.code());
            return created;
        }
    }

    /** 生成不与活跃码冲突的随机数字码。调用方须持 serviceLock。 */
    private String uniqueCode(long now) {
        BindSettings s = settings;
        for (int i = 0; i < 200; i++) {
            StringBuilder sb = new StringBuilder(s.codeLength);
            for (int d = 0; d < s.codeLength; d++) {
                sb.append(RANDOM.nextInt(10));
            }
            String code = sb.toString();
            if (!codes.containsKey(code) && !tombstones.containsKey(code)) {
                return code;
            }
        }
        // 4 位码空间 1 万，200 次全碰撞的概率 ~ 1e-8；兜底带时间位
        return String.valueOf((now % 100000) + 100000);
    }

    /** 清理过期码与墓碑。 */
    public int purgeExpired() {
        return purgeExpired(System.currentTimeMillis());
    }

    /** 清理过期码与墓碑（显式时钟，测试用）。 */
    public int purgeExpired(long now) {
        int removed = 0;
        synchronized (serviceLock) {
            for (Map.Entry<String, PendingCode> e : codes.entrySet()) {
                if (e.getValue().expired(now)) {
                    codes.remove(e.getKey());
                    codeByUuid.remove(e.getValue().uuid(), e.getKey());
                    removed++;
                }
            }
            tombstones.entrySet().removeIf(t -> t.getValue() < now);
            lastAttempt.entrySet().removeIf(t -> now - t.getValue() > 60_000L);
        }
        return removed;
    }

    public int activeCodeCount() {
        return codes.size();
    }

    public List<PendingCode> activeCodes() {
        return List.copyOf(codes.values());
    }

    /** 作废验证码：移出活跃表并立墓碑（区分"已使用"与"错误"）。 */
    private void invalidateCode(String code) {
        if (code == null) return;
        PendingCode removed = codes.remove(code);
        if (removed != null) {
            codeByUuid.remove(removed.uuid(), code);
            tombstones.put(code, System.currentTimeMillis() + settings.expireMillis);
        }
    }

    // ---------------- 查询 ----------------

    public boolean isBound(UUID uuid) {
        return store.isBound(uuid);
    }

    public List<BindStore.Binding> findByUuid(UUID uuid) {
        return store.findByUuid(uuid);
    }

    public List<BindStore.Binding> findByQq(long qq) {
        return store.findByQq(qq);
    }

    // ---------------- 冷却 ----------------

    /** 冷却检查+登记。未到冷却返回剩余秒数，否则登记本次并返回 0。 */
    public long checkCooldown(long qq, long now) {
        BindSettings s = settings;
        if (s.cooldownSeconds <= 0) return 0;
        Long last = lastAttempt.get(qq);
        if (last != null) {
            long elapsed = (now - last) / 1000L;
            if (elapsed < s.cooldownSeconds) {
                return s.cooldownSeconds - elapsed;
            }
        }
        lastAttempt.put(qq, now);
        return 0;
    }

    // ---------------- 绑定裁决 ----------------

    /**
     * 管理员代绑：跳过验证码（管理员指令即信任），仍走限额裁决。
     */
    public BindResult adminBind(UUID uuid, String name, long qq, long now) {
        BindSettings s = settings;
        synchronized (serviceLock) {
            if (store.isQqBanned(qq)) return BindResult.simple(Outcome.QQ_BANNED);
            for (BindStore.Binding b : store.findByUuid(uuid)) {
                if (b.qq() == qq) {
                    return new BindResult(Outcome.ALREADY_BOUND, b, null, 0); // 已存在，幂等
                }
            }
            int playerCount = store.countByUuid(uuid);
            if (playerCount >= s.maxPerPlayer) {
                if (s.limitPolicy == BindSettings.LimitPolicy.REPLACE && playerCount > 0) {
                    BindStore.Binding evicted = store.evictOldestOfUuid(uuid);
                    store.add(uuid, name, qq, now);
                    store.save();
                    return new BindResult(Outcome.SUCCESS_REPLACED,
                            new BindStore.Binding(uuid, name, qq, now), evicted, 0);
                }
                return BindResult.simple(Outcome.PLAYER_FULL);
            }
            int qqCount = store.countByQq(qq);
            if (qqCount >= s.maxPerQq) {
                if (s.limitPolicy == BindSettings.LimitPolicy.REPLACE && qqCount > 0) {
                    BindStore.Binding evicted = store.evictOldestOfQq(qq);
                    store.add(uuid, name, qq, now);
                    store.save();
                    return new BindResult(Outcome.SUCCESS_REPLACED,
                            new BindStore.Binding(uuid, name, qq, now), evicted, 0);
                }
                return BindResult.simple(Outcome.QQ_FULL);
            }
            store.add(uuid, name, qq, now);
            store.save();
            return new BindResult(Outcome.SUCCESS,
                    new BindStore.Binding(uuid, name, qq, now), null, 0);
        }
    }

    /** 管理员拉黑 QQ：入黑名单 + 清其名下全部绑定 + 落盘。返回清除绑定数。 */
    public int qqban(long qq, String reason) {
        int cleared = store.banQq(qq, System.currentTimeMillis(), reason);
        store.save();
        return cleared;
    }

    /** 管理员解除拉黑。返回是否原本在黑名单。 */
    public boolean qqunban(long qq) {
        boolean ok = store.unbanQq(qq);
        if (ok) store.save();
        return ok;
    }

    public BindResult attemptBind(String code, long qq, long now) {
        BindSettings s = settings;
        long cooldown = checkCooldown(qq, now);
        if (cooldown > 0) {
            return new BindResult(Outcome.COOLDOWN, null, null, cooldown);
        }
        // QQ 黑名单：拉黑后不可再绑定（含换号重绑）
        if (store.isQqBanned(qq)) {
            return BindResult.simple(Outcome.QQ_BANNED);
        }
        BindResult result;
        boolean mutated = false;
        synchronized (serviceLock) {
            PendingCode pending = codes.get(code);
            if (pending == null) {
                Long tsExpire = tombstones.get(code);
                if (tsExpire != null && tsExpire >= now) {
                    return BindResult.simple(Outcome.CODE_USED);
                }
                return BindResult.simple(Outcome.WRONG_CODE);
            }
            if (pending.expired(now)) {
                codes.remove(code);
                codeByUuid.remove(pending.uuid(), code);
                return BindResult.simple(Outcome.WRONG_CODE);
            }

            UUID uuid = pending.uuid();
            // 已存在同 uuid 同 qq 的绑定 → 幂等：作废验证码，返回 ALREADY_BOUND
            // （区别于 SUCCESS，让群内回执能如实告知"无需重复"）
            for (BindStore.Binding b : store.findByUuid(uuid)) {
                if (b.qq() == qq) {
                    invalidateCode(code);
                    return new BindResult(Outcome.ALREADY_BOUND, b, null, 0);
                }
            }

            int playerCount = store.countByUuid(uuid);
            if (playerCount >= s.maxPerPlayer) {
                if (s.limitPolicy == BindSettings.LimitPolicy.REPLACE && playerCount > 0) {
                    BindStore.Binding evicted = store.evictOldestOfUuid(uuid);
                    store.add(uuid, pending.name(), qq, now);
                    invalidateCode(code);
                    mutated = true;
                    result = new BindResult(Outcome.SUCCESS_REPLACED,
                            new BindStore.Binding(uuid, pending.name(), qq, now), evicted, 0);
                } else {
                    return BindResult.simple(Outcome.PLAYER_FULL);
                }
            } else {
                int qqCount = store.countByQq(qq);
                if (qqCount >= s.maxPerQq) {
                    if (s.limitPolicy == BindSettings.LimitPolicy.REPLACE && qqCount > 0) {
                        BindStore.Binding evicted = store.evictOldestOfQq(qq);
                        store.add(uuid, pending.name(), qq, now);
                        invalidateCode(code);
                        mutated = true;
                        result = new BindResult(Outcome.SUCCESS_REPLACED,
                                new BindStore.Binding(uuid, pending.name(), qq, now), evicted, 0);
                    } else {
                        return BindResult.simple(Outcome.QQ_FULL);
                    }
                } else {
                    store.add(uuid, pending.name(), qq, now);
                    invalidateCode(code);
                    mutated = true;
                    result = new BindResult(Outcome.SUCCESS,
                            new BindStore.Binding(uuid, pending.name(), qq, now), null, 0);
                }
            }
        }
        if (mutated) {
            store.save();
        }
        return result;
    }

    public List<BindStore.Binding> allBindings() {
        return store.all();
    }

    /** 管理员解绑玩家（全部绑定）。返回删除数。 */
    public int unbindPlayer(UUID uuid) {
        int n = store.removeAllByUuid(uuid);
        if (n > 0) store.save();
        return n;
    }

    /** 管理员解绑 QQ（全部绑定）。返回删除数。 */
    public int unbindQq(long qq) {
        int n = 0;
        for (BindStore.Binding b : findByQq(qq)) {
            if (store.removeExact(b.uuid(), qq)) n++;
        }
        if (n > 0) store.save();
        return n;
    }

    /** 群内自助解绑：解开发送者 QQ 与任意绑定（须 self-unbind 开启）。返回是否成功。 */
    public boolean selfUnbind(long qq, UUID uuid) {
        if (!settings.selfUnbind) return false;
        boolean ok = store.removeExact(uuid, qq);
        if (ok) store.save();
        return ok;
    }

    /**
     * 玩家指定解绑：按账号名解自己 QQ 名下的绑定（不区分大小写）。
 * 只影响发送者名下记录，不可越权。返回删除条数。
     */
    public int selfUnbindByName(long qq, String name) {
        if (!settings.selfUnbind) return 0;
        // QQ 黑名单：不可自助解绑（堵"解绑腾位→换号洗白"路径）
        if (store.isQqBanned(qq)) return 0;
        int n = 0;
        for (BindStore.Binding b : store.findByQq(qq)) {
            if (b.name().equalsIgnoreCase(name) && store.removeExact(b.uuid(), qq)) {
                n++;
            }
        }
        if (n > 0) store.save();
        return n;
    }

    /** 管理员精确解绑：删 uuid+qq 唯一确定的一条。返回是否删除。 */
    public boolean unbindExact(UUID uuid, long qq) {
        boolean ok = store.removeExact(uuid, qq);
        if (ok) store.save();
        return ok;
    }

    public BindStore store() {
        return store;
    }
}
