package dev.qqgate.bind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BindService 全链路裁决测试（纯 Java，无 Bukkit）。
 * 注：除冷却测试外均 cooldownSeconds(0)，假时钟只推进毫秒。
 */
class BindServiceTest {

    @TempDir
    Path dir;

    private BindStore store;
    private BindService svc;
    private long now = 1_000_000L;

    @BeforeEach
    void setUp() {
        store = new BindStore(dir, () -> false);
        store.load();
        svc = new BindService(store);
    }

    private void apply(BindSettings.Builder b) {
        svc.updateSettings(b.build());
    }

    // ---------------- 验证码 ----------------

    @Test
    void codeGeneratedAndLengthCorrect() {
        apply(new BindSettings.Builder());
        var code = svc.ensureCode(UUID.randomUUID(), "Steve", now);
        assertEquals(4, code.code().length());
        assertTrue(code.code().chars().allMatch(Character::isDigit));
        assertEquals(now + 5 * 60_000L, code.expiresAt());
    }

    @Test
    void customCodeLength() {
        apply(new BindSettings.Builder().codeLength(6));
        var code = svc.ensureCode(UUID.randomUUID(), "Steve", now);
        assertEquals(6, code.code().length());
    }

    @Test
    void refreshOnRejoinInvalidatesOldCode() {
        apply(new BindSettings.Builder());
        UUID u = UUID.randomUUID();
        var c1 = svc.ensureCode(u, "Steve", now);
        var c2 = svc.ensureCode(u, "Steve", now + 1000);
        assertNotEquals(c1.code(), c2.code());
        assertEquals(BindService.Outcome.WRONG_CODE,
                svc.attemptBind(c1.code(), 10001L, now + 2000).outcome());
    }

    @Test
    void noRefreshReturnsSameCode() {
        apply(new BindSettings.Builder().refreshOnRejoin(false));
        UUID u = UUID.randomUUID();
        var c1 = svc.ensureCode(u, "Steve", now);
        var c2 = svc.ensureCode(u, "Steve", now + 1000);
        assertEquals(c1.code(), c2.code());
    }

    @Test
    void expiredCodeRejected() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        var r = svc.attemptBind(c.code(), 10001L, now + 5 * 60_000L + 1);
        assertEquals(BindService.Outcome.WRONG_CODE, r.outcome());
    }

    @Test
    void codeSingleUse() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        assertEquals(BindService.Outcome.SUCCESS,
                svc.attemptBind(c.code(), 10001L, now + 100).outcome());
        assertEquals(BindService.Outcome.CODE_USED,
                svc.attemptBind(c.code(), 10002L, now + 200).outcome());
    }

    // ---------------- 绑定裁决 ----------------

    @Test
    void happyPathBindAndBound() {
        apply(new BindSettings.Builder());
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        var r = svc.attemptBind(c.code(), 10001L, now + 100);
        assertEquals(BindService.Outcome.SUCCESS, r.outcome());
        assertTrue(svc.isBound(u));
        assertEquals(1, svc.findByQq(10001L).size());
    }

    @Test
    void wrongCodeRejected() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        svc.ensureCode(UUID.randomUUID(), "Steve", now);
        assertEquals(BindService.Outcome.WRONG_CODE,
                svc.attemptBind("9999", 10001L, now + 100).outcome());
    }

    @Test
    void qqLimitReject() {
        apply(new BindSettings.Builder().maxPerQq(1).cooldownSeconds(0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var ca = svc.ensureCode(a, "A", now);
        var cb = svc.ensureCode(b, "B", now);
        svc.attemptBind(ca.code(), 10001L, now + 100);
        assertEquals(BindService.Outcome.QQ_FULL,
                svc.attemptBind(cb.code(), 10001L, now + 200).outcome());
    }

    @Test
    void qqLimitReplaceEvictsOldest() {
        apply(new BindSettings.Builder().maxPerQq(2)
                .limitPolicy(BindSettings.LimitPolicy.REPLACE).cooldownSeconds(0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var ca = svc.ensureCode(a, "A", now);
        svc.attemptBind(ca.code(), 10001L, now + 100);
        var cb = svc.ensureCode(b, "B", now + 1000);
        svc.attemptBind(cb.code(), 10001L, now + 1100);
        var cc = svc.ensureCode(c, "C", now + 2000);
        var r = svc.attemptBind(cc.code(), 10001L, now + 2100);
        assertEquals(BindService.Outcome.SUCCESS_REPLACED, r.outcome());
        assertEquals("A", r.evicted().name()); // 最早的被挤掉
        assertEquals(2, svc.findByQq(10001L).size());
        assertFalse(svc.isBound(a));
    }

    @Test
    void playerLimitRejectAndReplace() {
        apply(new BindSettings.Builder().maxPerPlayer(1).cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c1 = svc.ensureCode(u, "Steve", now);
        svc.attemptBind(c1.code(), 10001L, now + 100);
        var c2 = svc.ensureCode(u, "Steve", now + 200);
        assertEquals(BindService.Outcome.PLAYER_FULL,
                svc.attemptBind(c2.code(), 10002L, now + 300).outcome());

        // 切 replace 策略：新 QQ 挤掉旧 QQ
        apply(new BindSettings.Builder().maxPerPlayer(1)
                .limitPolicy(BindSettings.LimitPolicy.REPLACE).cooldownSeconds(0));
        var c3 = svc.ensureCode(u, "Steve", now + 400);
        var r = svc.attemptBind(c3.code(), 10002L, now + 500);
        assertEquals(BindService.Outcome.SUCCESS_REPLACED, r.outcome());
        assertEquals(10001L, r.evicted().qq());
        assertEquals(10002L, svc.findByUuid(u).get(0).qq());
    }

    @Test
    void cooldownBlocksRapidAttempts() {
        apply(new BindSettings.Builder().cooldownSeconds(10));
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        svc.attemptBind("0000", 10001L, now); // 错码也计入冷却
        var r = svc.attemptBind(c.code(), 10001L, now + 5_000);
        assertEquals(BindService.Outcome.COOLDOWN, r.outcome());
        assertEquals(5, r.retryAfterSeconds());
        assertEquals(BindService.Outcome.SUCCESS,
                svc.attemptBind(c.code(), 10001L, now + 10_001).outcome());
    }

    @Test
    void idempotentRebindSamePair() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c1 = svc.ensureCode(u, "Steve", now);
        svc.attemptBind(c1.code(), 10001L, now + 100);
        var c2 = svc.ensureCode(u, "Steve", now + 200);
        var r = svc.attemptBind(c2.code(), 10001L, now + 300);
        assertEquals(BindService.Outcome.ALREADY_BOUND, r.outcome()); // 幂等：如实告知已绑定
        assertEquals(1, store.countByQq(10001L)); // 不重复落库
        assertEquals("Steve", r.created().name()); // 幂等返回既有绑定
    }

    // ---------------- 持久化往返 ----------------

    @Test
    void persistenceRoundTrip() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        svc.attemptBind(c.code(), 10001L, now + 100);
        store.save();

        BindStore store2 = new BindStore(dir, () -> false);
        store2.load();
        assertEquals(1, store2.findByQq(10001L).size());
        assertEquals(u, store2.findByQq(10001L).get(0).uuid());
        assertEquals("Steve", store2.findByQq(10001L).get(0).name());
    }

    // ---------------- 解绑 ----------------

    @Test
    void unbindPlayerAndQq() {
        apply(new BindSettings.Builder().maxPerQq(2).cooldownSeconds(0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var ca = svc.ensureCode(a, "A", now);
        var cb = svc.ensureCode(b, "B", now);
        svc.attemptBind(ca.code(), 10001L, now + 100);
        svc.attemptBind(cb.code(), 10001L, now + 200);
        assertEquals(2, svc.unbindQq(10001L));
        assertFalse(svc.isBound(a));
        assertFalse(svc.isBound(b));
    }

    @Test
    void selfUnbindRequiresSetting() {
        apply(new BindSettings.Builder().cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        var c = svc.ensureCode(u, "Steve", now);
        svc.attemptBind(c.code(), 10001L, now + 100);
        assertFalse(svc.selfUnbind(10001L, u)); // 默认关

        apply(new BindSettings.Builder().selfUnbind(true).cooldownSeconds(0));
        assertTrue(svc.selfUnbind(10001L, u));
        assertFalse(svc.isBound(u));
    }

    // ---------------- 清理 ----------------

    @Test
    void purgeExpiredRemovesCodesAndTombstones() {
        apply(new BindSettings.Builder());
        svc.ensureCode(UUID.randomUUID(), "A", now);
        int removed = svc.purgeExpired(now + 10 * 60_000L);
        assertEquals(1, removed);
        assertEquals(0, svc.activeCodeCount());
    }

    @Test
    void selfUnbindByNameTargetsOwnBindingsOnly() {
        apply(new BindSettings.Builder().maxPerQq(3).selfUnbind(true).cooldownSeconds(0));
        UUID mine1 = UUID.randomUUID();
        UUID mine2 = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var c1 = svc.ensureCode(mine1, "Steve", now);
        var c2 = svc.ensureCode(mine2, "Alex", now);
        var c3 = svc.ensureCode(other, "Steve", now); // 别人的 Steve（另一个QQ绑）
        svc.attemptBind(c1.code(), 10001L, now + 100);
        svc.attemptBind(c2.code(), 10001L, now + 200);
        svc.attemptBind(c3.code(), 10002L, now + 300);

        // 解自己名下 Steve：只删 10001-Steve，不动 Alex 和 10002-Steve
        assertEquals(1, svc.selfUnbindByName(10001L, "steve")); // 大小写不敏感
        assertFalse(svc.isBound(mine1));
        assertTrue(svc.isBound(mine2));
        assertTrue(svc.isBound(other)); // 他人的 Steve 绑定不受影响

        // 名字不在自己名下
        assertEquals(0, svc.selfUnbindByName(10001L, "Notch"));
        assertTrue(svc.isBound(mine2));
    }

    @Test
    void unbindExactRemovesSinglePair() {
        apply(new BindSettings.Builder().maxPerQq(2).maxPerPlayer(2).cooldownSeconds(0));
        UUID u = UUID.randomUUID();
        // 注意顺序：每次 ensureCode 会作废上一个码（refresh-on-rejoin），
        // 必须 拿码→绑定→再拿下一个码
        var c1 = svc.ensureCode(u, "Steve", now);
        svc.attemptBind(c1.code(), 10001L, now + 100);
        var c2 = svc.ensureCode(u, "Steve", now + 200);
        svc.attemptBind(c2.code(), 10002L, now + 300);

        assertTrue(svc.unbindExact(u, 10001L));
        assertFalse(svc.unbindExact(u, 10001L)); // 重复删返回 false
        assertEquals(1, svc.findByUuid(u).size());
        assertEquals(10002L, svc.findByUuid(u).get(0).qq());
    }
}
