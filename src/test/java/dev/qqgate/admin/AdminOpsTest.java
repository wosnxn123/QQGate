package dev.qqgate.admin;

import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindSettings;
import dev.qqgate.bind.BindStore;
import dev.qqgate.util.QqId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdminOps 编排层测试（纯 Java，无 Bukkit）：真实 BindService + BindStore，临时目录。
 * <p>前置统一放宽限额（3/3、无冷却），便于构造多条绑定；
 * 个别用例在方法内覆盖为 REPLACE 策略。
 */
class AdminOpsTest {

    @TempDir
    Path dir;

    private BindStore store;
    private BindService svc;
    private AdminOps ops;
    private long now = 1_700_000_000_000L;

    @BeforeEach
    void setUp() {
        store = new BindStore(dir, () -> false);
        store.load();
        svc = new BindService(store);
        svc.updateSettings(new BindSettings.Builder()
                .maxPerQq(3).maxPerPlayer(3).cooldownSeconds(0).build());
        ops = new AdminOps(svc);
    }

    private static QqId qq(String digits) {
        return QqId.parse(digits).orElseThrow();
    }

    /** 直接走代绑落一条绑定，返回该玩家 UUID。 */
    private UUID seed(String player, long qq) {
        UUID u = UUID.randomUUID();
        assertEquals(BindService.Outcome.SUCCESS, svc.adminBind(u, player, qq, now).outcome());
        return u;
    }

    // ---------------- 目标解析 ----------------

    @Test
    void targetDigitsParseAsQq() {
        AdminOps.Target t = AdminOps.target("12345");
        assertInstanceOf(AdminOps.QqTarget.class, t);
        assertEquals(12345L, ((AdminOps.QqTarget) t).qq().value());
    }

    @Test
    void targetNameFallsToNameTarget() {
        AdminOps.Target t = AdminOps.target("Steve");
        assertInstanceOf(AdminOps.NameTarget.class, t);
        assertEquals("Steve", ((AdminOps.NameTarget) t).name());
    }

    @Test
    void targetOverlongDigitsFallToNameTarget() {
        // 20 位纯数字超过 18 位闸门：当玩家名处理，而不是抛 NumberFormatException
        AdminOps.Target t = AdminOps.target("99999999999999999999");
        assertInstanceOf(AdminOps.NameTarget.class, t);
        assertEquals("99999999999999999999", ((AdminOps.NameTarget) t).name());
    }

    @Test
    void targetEmptyIsNameTarget() {
        AdminOps.Target t = AdminOps.target("");
        assertInstanceOf(AdminOps.NameTarget.class, t);
        assertEquals("", ((AdminOps.NameTarget) t).name());
    }

    // ---------------- 查询 ----------------

    @Test
    void lookupQqHitsMultipleBindings() {
        seed("Alice", 10001L);
        seed("Bob", 10001L);
        AdminOps.LookupResult r = ops.lookup(AdminOps.target("10001"));
        assertEquals(AdminOps.LookupResult.Resolution.BY_QQ, r.resolution());
        assertEquals(2, r.bindings().size());
        assertTrue(r.ban().isEmpty());
    }

    @Test
    void lookupNameHit() {
        seed("Steve", 10001L);
        AdminOps.LookupResult r = ops.lookup(AdminOps.target("Steve"));
        assertEquals(AdminOps.LookupResult.Resolution.BY_NAME, r.resolution());
        assertEquals(1, r.bindings().size());
        assertEquals(10001L, r.bindings().get(0).qq());
        assertEquals("Steve", r.bindings().get(0).name());
    }

    @Test
    void lookupMissOnBothKinds() {
        assertTrue(ops.lookup(AdminOps.target("Nobody")).bindings().isEmpty());
        AdminOps.LookupResult qqMiss = ops.lookup(AdminOps.target("4321"));
        assertEquals(AdminOps.LookupResult.Resolution.BY_QQ, qqMiss.resolution());
        assertTrue(qqMiss.bindings().isEmpty());
    }

    @Test
    void lookupBannedQqCarriesReasonAndTime() {
        seed("Steve", 10001L);
        svc.qqban(10001L, "作弊");
        AdminOps.LookupResult r = ops.lookup(AdminOps.target("10001"));
        assertTrue(r.ban().isPresent());
        AdminOps.BanInfo ban = r.ban().get();
        assertEquals(10001L, ban.qq());
        assertEquals("作弊", ban.reason());
        // 拉黑时间透传存储原值，可解析为正数毫秒（BindService.qqban 用系统时钟）
        assertTrue(Long.parseLong(ban.bannedAtRaw()) > 0);
        // 被拉黑不影响绑定可见：查得到 1 条
        assertEquals(1, r.bindings().size());
    }

    @Test
    void lookupPrefersUuidOverName() {
        UUID u = seed("Steve", 10001L);
        // UUID 命中 → BY_UUID
        AdminOps.LookupResult byUuid = ops.lookup(AdminOps.target("Steve"), u);
        assertEquals(AdminOps.LookupResult.Resolution.BY_UUID, byUuid.resolution());
        assertEquals(1, byUuid.bindings().size());
        // UUID 无绑定 → 回退名字检索
        AdminOps.LookupResult byName = ops.lookup(AdminOps.target("Steve"), UUID.randomUUID());
        assertEquals(AdminOps.LookupResult.Resolution.BY_NAME, byName.resolution());
        assertEquals(1, byName.bindings().size());
    }

    // ---------------- 解绑（三态） ----------------

    @Test
    void unbindNoBinding() {
        assertInstanceOf(AdminOps.UnbindResult.NoBinding.class,
                ops.unbind(AdminOps.target("Ghost")));
        assertInstanceOf(AdminOps.UnbindResult.NoBinding.class,
                ops.unbind(AdminOps.target("4321")));
    }

    @Test
    void unbindSingleHitRemovesAndCountsRemaining() {
        seed("Steve", 10001L);
        AdminOps.UnbindResult r = ops.unbind(AdminOps.target("Steve"));
        assertInstanceOf(AdminOps.UnbindResult.Single.class, r);
        AdminOps.UnbindResult.Single single = (AdminOps.UnbindResult.Single) r;
        assertEquals("Steve", single.removed().name());
        assertEquals(10001L, single.removed().qq());
        assertEquals(0, single.remaining());
        assertTrue(svc.findByQq(10001L).isEmpty());
    }

    @Test
    void unbindAmbiguousListsCandidatesWithoutRemoving() {
        seed("Alice", 10001L);
        seed("Bob", 10001L);
        AdminOps.UnbindResult r = ops.unbind(AdminOps.target("10001"));
        assertInstanceOf(AdminOps.UnbindResult.Ambiguous.class, r);
        AdminOps.UnbindResult.Ambiguous amb = (AdminOps.UnbindResult.Ambiguous) r;
        assertEquals(2, amb.candidates().size());
        // 歧义不代选：一条都没删
        assertEquals(2, svc.findByQq(10001L).size());
    }

    @Test
    void unbindUuidFallbackRemovesOrphans() {
        // 绑定记录的名字是旧名，按新名查不到 → 游戏内用 UUID 回退清孤儿
        UUID u = seed("OldName", 10001L);
        assertInstanceOf(AdminOps.UnbindResult.NoBinding.class,
                ops.unbind(AdminOps.target("NewName"))); // QQ 侧无 UUID 来源
        AdminOps.UnbindResult r = ops.unbind(AdminOps.target("NewName"), u);
        assertInstanceOf(AdminOps.UnbindResult.ByUuid.class, r);
        assertEquals(1, ((AdminOps.UnbindResult.ByUuid) r).removed());
        assertTrue(svc.findByQq(10001L).isEmpty());
    }

    // ---------------- 精确解绑 ----------------

    @Test
    void unbindExactHitAndRemaining() {
        UUID u = seed("Steve", 10001L);
        assertEquals(BindService.Outcome.SUCCESS, svc.adminBind(u, "Steve", 10002L, now).outcome());
        AdminOps.ExactUnbindResult r = ops.unbindExact("Steve", qq("10001"));
        assertInstanceOf(AdminOps.ExactUnbindResult.Removed.class, r);
        AdminOps.ExactUnbindResult.Removed removed = (AdminOps.ExactUnbindResult.Removed) r;
        assertEquals(1, removed.removed());
        assertEquals(1, removed.remaining());
        assertEquals(10002L, svc.findByUuid(u).get(0).qq());
    }

    @Test
    void unbindExactNotFoundCombo() {
        seed("Steve", 10001L);
        assertInstanceOf(AdminOps.ExactUnbindResult.NotFound.class,
                ops.unbindExact("Steve", qq("9999")));
        assertInstanceOf(AdminOps.ExactUnbindResult.NotFound.class,
                ops.unbindExact("Nobody", qq("10001")));
    }

    // ---------------- 全解绑 ----------------

    @Test
    void unbindAllQqTarget() {
        seed("Alice", 10001L);
        seed("Bob", 10001L);
        AdminOps.UnbindAllResult r = ops.unbindAll(AdminOps.target("10001"));
        assertEquals(2, r.removed());
        assertEquals(2, r.details().size());
        assertTrue(r.details().containsAll(java.util.List.of("Alice", "Bob")));
        assertTrue(svc.findByQq(10001L).isEmpty());
    }

    @Test
    void unbindAllNameTarget() {
        UUID u = seed("Steve", 10001L);
        assertEquals(BindService.Outcome.SUCCESS, svc.adminBind(u, "Steve", 10002L, now).outcome());
        AdminOps.UnbindAllResult r = ops.unbindAll(AdminOps.target("Steve"));
        assertEquals(2, r.removed());
        assertTrue(r.details().containsAll(java.util.List.of("10001", "10002")));
        assertTrue(svc.findByUuid(u).isEmpty());
    }

    @Test
    void unbindAllNothingToClear() {
        AdminOps.UnbindAllResult r = ops.unbindAll(AdminOps.target("Ghost"));
        assertEquals(0, r.removed());
        assertTrue(r.details().isEmpty());
    }

    // ---------------- 拉黑 / 解拉黑 ----------------

    @Test
    void banWithReasonTrimsAndBlocksNames() {
        seed("Steve", 10001L);
        AdminOps.BanResult r = ops.qqban(qq("10001"), "  作弊  ");
        assertEquals(10001L, r.qq());
        assertEquals("作弊", r.reason());
        assertEquals(java.util.List.of("Steve"), r.blockedNames());
        assertTrue(store.isQqBanned(10001L));
    }

    @Test
    void banWithoutReason() {
        AdminOps.BanResult r = ops.qqban(qq("20002"), null);
        assertEquals("", r.reason());
        assertTrue(r.blockedNames().isEmpty());
        assertTrue(store.isQqBanned(20002L));
    }

    @Test
    void unbanReportsWasBanned() {
        assertFalse(ops.qqunban(qq("30003")).wasBanned());
        ops.qqban(qq("30003"), "");
        assertTrue(ops.qqunban(qq("30003")).wasBanned());
        assertFalse(store.isQqBanned(30003L));
    }

    @Test
    void banListCarriesTimeReasonAndNames() {
        seed("Steve", 10001L);
        ops.qqban(qq("10001"), "作弊");
        ops.qqban(qq("20002"), null);
        var list = ops.banList();
        assertEquals(2, list.size());
        AdminOps.BanListEntry first = list.get(0);
        assertEquals(10001L, first.ban().qq());
        assertEquals("作弊", first.ban().reason());
        assertEquals(java.util.List.of("Steve"), first.names());
        assertTrue(Long.parseLong(first.ban().bannedAtRaw()) > 0);
    }

    // ---------------- 代绑 ----------------

    @Test
    void adminBindSuccessUuidPath() {
        UUID u = UUID.randomUUID();
        AdminOps.AdminBindResult r = ops.adminBind(u, "Steve", qq("10001"), now);
        assertEquals(AdminOps.AdminBindResult.Outcome.SUCCESS, r.outcome());
        assertEquals("Steve", r.player());
        assertEquals(10001L, r.qq());
        assertEquals(1, r.qqBindings());
        assertEquals(1, svc.findByUuid(u).size());
    }

    @Test
    void adminBindByNameSuccess() {
        UUID u = seed("Steve", 10001L);
        AdminOps.AdminBindResult r = ops.adminBindByName("Steve", qq("10002"), now);
        assertEquals(AdminOps.AdminBindResult.Outcome.SUCCESS, r.outcome());
        // 定位到既有记录的 UUID，而不是新建身份
        assertEquals(2, svc.findByUuid(u).size());
    }

    @Test
    void adminBindRejectedForBannedQq() {
        UUID u = seed("Steve", 10001L);
        ops.qqban(qq("10002"), "作弊");
        AdminOps.AdminBindResult r = ops.adminBind(u, "Steve", qq("10002"), now);
        assertEquals(AdminOps.AdminBindResult.Outcome.QQ_BANNED, r.outcome());
        assertEquals(1, svc.findByUuid(u).size()); // 未新增
    }

    @Test
    void adminBindByNameWithoutRecordRejected() {
        AdminOps.AdminBindResult r = ops.adminBindByName("Ghost", qq("10001"), now);
        assertEquals(AdminOps.AdminBindResult.Outcome.NO_PLAYER_RECORD, r.outcome());
        assertTrue(svc.allBindings().isEmpty());
    }

    @Test
    void adminBindByNameAlreadyBound() {
        seed("Steve", 10001L);
        AdminOps.AdminBindResult r = ops.adminBindByName("Steve", qq("10001"), now);
        assertEquals(AdminOps.AdminBindResult.Outcome.ALREADY_BOUND, r.outcome());
    }

    @Test
    void replacePolicyReportsEvicted() {
        svc.updateSettings(new BindSettings.Builder()
                .maxPerQq(1).maxPerPlayer(3).cooldownSeconds(0)
                .limitPolicy(BindSettings.LimitPolicy.REPLACE).build());
        seed("Alice", 10001L);
        UUID uB = UUID.randomUUID();
        AdminOps.AdminBindResult r = ops.adminBind(uB, "Bob", qq("10001"), now + 1000);
        assertEquals(AdminOps.AdminBindResult.Outcome.SUCCESS_REPLACED, r.outcome());
        assertTrue(r.evicted().isPresent());
        assertEquals("Alice", r.evicted().get());
        assertEquals(1, svc.findByQq(10001L).size());
        assertEquals("Bob", svc.findByQq(10001L).get(0).name());
    }

    // ---------------- 状态计数 ----------------

    @Test
    void statusCountsBindsBansAndCodes() {
        UUID u = seed("Steve", 10001L);
        ops.qqban(qq("20002"), null);
        svc.ensureCode(u, "Steve", now);
        AdminOps.StatusCounts c = ops.statusCounts();
        assertEquals(1, c.bindings());
        assertEquals(1, c.banned());
        assertEquals(1, c.activeCodes());
    }
}
