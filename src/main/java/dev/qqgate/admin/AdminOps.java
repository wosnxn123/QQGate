package dev.qqgate.admin;

import dev.qqgate.bind.BindService;
import dev.qqgate.bind.BindStore;
import dev.qqgate.util.QqId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理操作的业务编排层：QQ 侧（ChatMessageHandler）与游戏内（QQGateAdminCommand）
 * 共用的判断逻辑，单入口。只做业务判断与计数，返回结构化 record——一个字符串都不拼；
 * 文案渲染留给两个表现层（QQ 侧 messages 模板 / 游戏内 {@code dev.qqgate.command.Msg}）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>{@link BindService} / {@link BindStore}：存储与裁决规则（限额、黑名单、落盘）——
 *       本类不重复实现，只调用；</li>
 *   <li>本类：两个表现层过去各自重复实现的"编排"——目标解析（数字→QQ，否则玩家名）、
 *       歧义检测（多条绑定列出候选）、名字未命中时的 UUID 回退、结果计数；</li>
 *   <li>表现层：把结果 record 渲染成各自渠道的文案。时间格式化也归表现层
 *       （{@code TimeFmt.Preset.LIST}），本类只传原值。</li>
 * </ul>
 *
 * <h2>名字→UUID 的边界</h2>
 * UUID 解析依赖 Bukkit（OfflinePlayer），本类保持纯 JVM 以便单测：游戏内调用方把解析好的
 * UUID 经 {@code resolvedUuid} 参数传入（解析不了传 null），QQ 侧调用方一律不传。
 * <b>判定逻辑</b>（何时走 UUID、何时回退名字、孤儿绑定何时清）仍集中在本类，
 * 两面的行为差异由此统一。
 *
 * <h2>与 OneBotEndpoint 的边界</h2>
 * 「状态」只包含 BindService 能回答的计数（{@link #statusCounts()}）；
 * 连接状态（mode / connected / self_id / 连接时长）是 QQ 侧独有的网络层信息，
 * 刻意不拖进本类——否则游戏内侧会被迫依赖网络层。
 *
 * <h2>时间戳约定</h2>
 * 绑定时间（boundAt）以 long 原值透传，本层不格式化。拉黑时间是存储里的字符串
 * （{@code banned_qqs.json} 的 {@code String[]}，可能被人手改坏），以原值字符串透传，
 * 展示走 {@code TimeFmt.formatSafe(Preset.LIST, raw, ...)}，解析失败显示"时间未知"，
 * 一条脏数据不炸掉整个列表。
 */
public final class AdminOps {

    private final BindService binds;

    public AdminOps(BindService binds) {
        this.binds = binds;
    }

    // ================= 目标解析 =================

    /** 指令目标：可解析的纯数字是 QQ 号，其余一律玩家名。 */
    public sealed interface Target permits QqTarget, NameTarget {
    }

    /** 数字目标：QQ 号（已经过 {@link QqId} 的位数与字符合法性校验）。 */
    public record QqTarget(QqId qq) implements Target {
    }

    /**
     * 名字目标：玩家名。超长纯数字串（如 20 位）被 {@link QqId#MAX_DIGITS} 闸门
     * 拒收后也落在这里——当玩家名处理，查无结果就是查无结果，不再有
     * {@code Long.parseLong} 抛 {@code NumberFormatException} 的路径。
     */
    public record NameTarget(String name) implements Target {
    }

    /**
     * 解析指令参数为目标。
     * <p>统一两面曾经不一致的 isNumeric：QQ 侧有 18 位闸门、游戏内侧没有（且认
     * Unicode 数字）。现在一律走 {@link QqId#parse(String)}：ASCII 数字且不超过
     * {@link QqId#MAX_DIGITS} 位才算 QQ，其余（含空串）是玩家名。
     *
     * @param raw 指令参数原文，须非 null
     */
    public static Target target(String raw) {
        Objects.requireNonNull(raw, "raw");
        return QqId.parse(raw).<Target>map(QqTarget::new)
                .orElseGet(() -> new NameTarget(raw));
    }

    // ================= 黑名单条目 =================

    /**
     * 一条黑名单记录。
     * <p>拉黑时间在存储里就是字符串且可能被手改坏，故透传原值（见类注释），
     * 本层不解析、不格式化。
     *
     * @param bannedAtRaw 拉黑时间戳的存储原值（字符串；脏值由 formatSafe 兜底）
     * @param reason      拉黑原因，无原因为空串（不为 null）
     */
    public record BanInfo(long qq, String bannedAtRaw, String reason) {
    }

    /** 存储条目 → {@link BanInfo}：容忍旧格式/手改导致的缺字段。 */
    private static BanInfo toBanInfo(long qq, String[] meta) {
        String raw = meta != null && meta.length > 0 && meta[0] != null ? meta[0] : "";
        String reason = meta != null && meta.length > 1 && meta[1] != null ? meta[1] : "";
        return new BanInfo(qq, raw, reason);
    }

    // ================= 查询 =================

    /** 双向查询结果。{@code bindings} 空即查无结果。 */
    public record LookupResult(Target target, List<BindStore.Binding> bindings,
                               Resolution resolution, Optional<BanInfo> ban) {
        /** 命中途径：渲染层据此选择措辞（如游戏内"按名字找到"附改名提示）。 */
        public enum Resolution {
            /** 按 QQ 号检索。 */
            BY_QQ,
            /** 按调用方解析的 UUID 命中（游戏内主路径）。 */
            BY_UUID,
            /** 按名字检索（QQ 侧唯一途径；游戏内为 UUID 未命中的回退，可能是改名后的历史绑定）。 */
            BY_NAME
        }
    }

    /** 查询（无 UUID 解析途径，QQ 侧用）。 */
    public LookupResult lookup(Target target) {
        return lookup(target, null);
    }

    /**
     * 双向查询。
     * <p>统一两面差异：游戏内原来先按 UUID 命中、落空再按名字；QQ 侧只按名字。
     * 现在由本方法集中裁决——{@code resolvedUuid} 非 null 时优先 UUID。
     *
     * @param resolvedUuid 名字目标时调用方解析出的玩家 UUID；QQ 侧或解析失败传 null
     */
    public LookupResult lookup(Target target, UUID resolvedUuid) {
        if (target instanceof QqTarget t) {
            long qq = t.qq().value();
            Optional<BanInfo> ban = Optional.empty();
            if (binds.store().isQqBanned(qq)) {
                ban = Optional.of(toBanInfo(qq, binds.store().bannedQqs().get(qq)));
            }
            return new LookupResult(target, List.copyOf(binds.findByQq(qq)),
                    LookupResult.Resolution.BY_QQ, ban);
        }
        NameTarget n = (NameTarget) target;
        if (resolvedUuid != null) {
            List<BindStore.Binding> byUuid = binds.findByUuid(resolvedUuid);
            if (!byUuid.isEmpty()) {
                return new LookupResult(target, List.copyOf(byUuid),
                        LookupResult.Resolution.BY_UUID, Optional.empty());
            }
        }
        return new LookupResult(target, byName(n.name()),
                LookupResult.Resolution.BY_NAME, Optional.empty());
    }

    // ================= 解绑（单条直解 / 歧义） =================

    /**
     * 解绑结果三态（名字目标的 UUID 回退成功单列一态）。
     * <p>不用 null 哨兵：渲染层对 sealed 子类型做穷举 switch，新增状态编译期报错。
     */
    public sealed interface UnbindResult
            permits UnbindResult.NoBinding, UnbindResult.Single,
                    UnbindResult.Ambiguous, UnbindResult.ByUuid {

        /** 无任何绑定可解。 */
        record NoBinding(Target target) implements UnbindResult {
        }

        /**
         * 唯一命中，已解绑。
         *
         * @param removed   被解掉的那条绑定（含玩家名/QQ/时间戳原值）
         * @param remaining 解绑后该目标名下剩余条数（实时计数，不再硬编码 0）
         */
        record Single(Target target, BindStore.Binding removed, int remaining) implements UnbindResult {
        }

        /** 多条绑定，不能代选：列出候选让调用方引导精确解绑或全解绑。 */
        record Ambiguous(Target target, List<BindStore.Binding> candidates) implements UnbindResult {
        }

        /** 名字未命中、但按调用方提供的 UUID 解掉了改名遗留的孤儿绑定（仅游戏内路径可能产生）。 */
        record ByUuid(Target target, int removed) implements UnbindResult {
        }
    }

    /** 解绑（无 UUID 解析途径，QQ 侧用）。 */
    public UnbindResult unbind(Target target) {
        return unbind(target, null);
    }

    /**
     * 解绑：唯一命中直解；多条返回候选；名字未命中时（游戏内）回退 UUID 清孤儿绑定。
     * <p>统一两面差异：游戏内原来有 UUID 回退、QQ 侧没有，现在由 {@code resolvedUuid}
     * 是否提供决定，判断逻辑集中于此。
     *
     * @param resolvedUuid 名字目标时调用方解析出的玩家 UUID；QQ 侧或解析失败传 null
     */
    public UnbindResult unbind(Target target, UUID resolvedUuid) {
        if (target instanceof QqTarget t) {
            long qq = t.qq().value();
            List<BindStore.Binding> list = binds.findByQq(qq);
            if (list.isEmpty()) {
                return new UnbindResult.NoBinding(target);
            }
            if (list.size() == 1) {
                BindStore.Binding only = list.get(0);
                binds.unbindExact(only.uuid(), qq);
                return new UnbindResult.Single(target, only, binds.findByQq(qq).size());
            }
            return new UnbindResult.Ambiguous(target, List.copyOf(list));
        }
        NameTarget n = (NameTarget) target;
        List<BindStore.Binding> list = byName(n.name());
        if (list.size() == 1) {
            BindStore.Binding only = list.get(0);
            binds.unbindExact(only.uuid(), only.qq());
            return new UnbindResult.Single(target, only, byName(n.name()).size());
        }
        if (!list.isEmpty()) {
            return new UnbindResult.Ambiguous(target, list);
        }
        // 名字未命中：游戏内回退 UUID（改名产生的孤儿绑定）；QQ 侧无 UUID 来源，只能报无绑定
        if (resolvedUuid != null) {
            int removed = binds.unbindPlayer(resolvedUuid);
            if (removed > 0) {
                return new UnbindResult.ByUuid(target, removed);
            }
        }
        return new UnbindResult.NoBinding(target);
    }

    // ================= 精确解绑 =================

    /** 精确解绑（玩家名 + QQ 号）两态结果。 */
    public sealed interface ExactUnbindResult
            permits ExactUnbindResult.NotFound, ExactUnbindResult.Removed {

        /** 无此组合的绑定。 */
        record NotFound(String player, long qq) implements ExactUnbindResult {
        }

        /**
         * 已删除。
         *
         * @param removed   删除条数（同名同 QQ 可能存在多条不同 UUID 的记录，全部删除）
         * @param remaining 该玩家名下剩余条数
         */
        record Removed(String player, long qq, int removed, int remaining) implements ExactUnbindResult {
        }
    }

    /** 精确解绑一条（玩家名忽略大小写）。QQ 以 {@link QqId} 传入，格式校验前置到调用方。 */
    public ExactUnbindResult unbindExact(String player, QqId qq) {
        long q = qq.value();
        List<BindStore.Binding> matches = binds.allBindings().stream()
                .filter(b -> b.name().equalsIgnoreCase(player) && b.qq() == q)
                .toList();
        if (matches.isEmpty()) {
            return new ExactUnbindResult.NotFound(player, q);
        }
        int removed = 0;
        for (BindStore.Binding b : matches) {
            if (binds.unbindExact(b.uuid(), b.qq())) {
                removed++;
            }
        }
        return new ExactUnbindResult.Removed(player, q, removed, byName(player).size());
    }

    // ================= 全解绑 =================

    /**
     * 全解绑结果。
     *
     * @param removed 清空条数，0 即无绑定
     * @param details QQ 目标为被清的玩家名列表；玩家名目标为被清的 QQ 号列表（渲染层用"、"连接）
     */
    public record UnbindAllResult(Target target, int removed, List<String> details) {
    }

    /** 全解绑（无 UUID 解析途径，QQ 侧用）。 */
    public UnbindAllResult unbindAll(Target target) {
        return unbindAll(target, null);
    }

    /**
     * 全解绑：清空目标名下全部绑定。名字未命中时（游戏内）回退 UUID 清孤儿绑定。
     *
     * @param resolvedUuid 名字目标时调用方解析出的玩家 UUID；QQ 侧或解析失败传 null
     */
    public UnbindAllResult unbindAll(Target target, UUID resolvedUuid) {
        if (target instanceof QqTarget t) {
            long qq = t.qq().value();
            // 先取快照再删：details 必须是"被清掉的"那批
            List<BindStore.Binding> list = binds.findByQq(qq);
            int n = binds.unbindQq(qq);
            return new UnbindAllResult(target, n, list.stream().map(BindStore.Binding::name).toList());
        }
        NameTarget n = (NameTarget) target;
        List<BindStore.Binding> list = byName(n.name());
        int removed = 0;
        for (BindStore.Binding b : list) {
            if (binds.unbindExact(b.uuid(), b.qq())) {
                removed++;
            }
        }
        List<String> details = list.stream().map(b -> String.valueOf(b.qq())).toList();
        if (removed == 0 && resolvedUuid != null) {
            // 名字未命中：回退 UUID 清掉改名遗留的孤儿绑定
            List<BindStore.Binding> orphans = binds.findByUuid(resolvedUuid);
            removed = binds.unbindPlayer(resolvedUuid);
            details = orphans.stream().map(b -> String.valueOf(b.qq())).toList();
        }
        return new UnbindAllResult(target, removed, details);
    }

    // ================= 拉黑 / 解拉黑 =================

    /**
     * 拉黑结果。
     *
     * @param reason       规范化后的原因（null→空串，去首尾空白）
     * @param blockedNames 该 QQ 名下被封锁的账号名（绑定保留作案底；解拉黑后自动复原）
     */
    public record BanResult(long qq, String reason, List<String> blockedNames) {
    }

    /** 解除拉黑结果。{@code wasBanned=false} 表示该 QQ 本就不在黑名单。 */
    public record UnbanResult(long qq, boolean wasBanned) {
    }

    /** 黑名单列表条目：{@code names} 为该 QQ 名下账号（渲染层用"、"连接）。 */
    public record BanListEntry(BanInfo ban, List<String> names) {
    }

    /** 拉黑 QQ：绑定保留作案底，名下账号名随之封锁。重复拉黑幂等更新原因。 */
    public BanResult qqban(QqId qq, String reason) {
        String normalized = reason == null ? "" : reason.trim();
        List<String> names = binds.qqban(qq.value(), normalized);
        return new BanResult(qq.value(), normalized, List.copyOf(names));
    }

    /** 解除拉黑：绑定未动，账号复原。 */
    public UnbanResult qqunban(QqId qq) {
        return new UnbanResult(qq.value(), binds.qqunban(qq.value()));
    }

    /** 黑名单快照（保持存储顺序）。 */
    public List<BanListEntry> banList() {
        var bans = binds.store().bannedQqs();
        List<BanListEntry> out = new ArrayList<>(bans.size());
        bans.forEach((qq, meta) ->
                out.add(new BanListEntry(toBanInfo(qq, meta), binds.store().namesOfQq(qq))));
        return out;
    }

    // ================= 代绑 =================

    /** 代绑结果。字段覆盖两面渲染所需：挤下的玩家、限额、该 QQ 当前条数。 */
    public record AdminBindResult(Outcome outcome, String player, long qq,
                                  Optional<String> evicted, int qqBindings,
                                  int maxPerQq, int maxPerPlayer) {
        /**
         * 代绑结局：与 {@link BindService.Outcome} 的可达子集一一对应，
         * 另加名字无记录导致无法定位 UUID 的 {@link #NO_PLAYER_RECORD}。
         */
        public enum Outcome {
            SUCCESS, SUCCESS_REPLACED, ALREADY_BOUND,
            QQ_FULL, PLAYER_FULL, QQ_BANNED,
            /** 仅 {@link #adminBindByName}：名字无任何既有绑定记录，离线名没有 UUID 来源。 */
            NO_PLAYER_RECORD
        }
    }

    /**
     * 代绑（调用方已解析 UUID，游戏内路径）。跳过验证码（管理员指令即信任），
     * 限额与黑名单裁决仍由 {@link BindService#adminBind} 生效。
     */
    public AdminBindResult adminBind(UUID uuid, String player, QqId qq, long now) {
        BindService.BindResult r = binds.adminBind(uuid, player, qq.value(), now);
        return toAdminBindResult(player, qq.value(), r);
    }

    /**
     * 代绑（无 UUID 来源，QQ 侧路径）：仅当该名字存在任意既有绑定记录时才能定位
     * UUID，否则返回 {@link AdminBindResult.Outcome#NO_PLAYER_RECORD}
     * （提示让玩家先进服或走游戏内命令）。
     * <p>名字+QQ 已存在时直接 {@code ALREADY_BOUND}（保留 QQ 侧既有语义：
     * 按名字对而非 UUID 对判重）。
     */
    public AdminBindResult adminBindByName(String player, QqId qq, long now) {
        long q = qq.value();
        boolean pairExists = binds.allBindings().stream()
                .anyMatch(b -> b.name().equalsIgnoreCase(player) && b.qq() == q);
        if (pairExists) {
            return adminBindResult(AdminBindResult.Outcome.ALREADY_BOUND, player, q, null);
        }
        List<BindStore.Binding> candidates = byName(player);
        if (candidates.isEmpty()) {
            return adminBindResult(AdminBindResult.Outcome.NO_PLAYER_RECORD, player, q, null);
        }
        return adminBind(candidates.get(0).uuid(), player, qq, now);
    }

    private AdminBindResult toAdminBindResult(String player, long qq, BindService.BindResult r) {
        AdminBindResult.Outcome outcome = switch (r.outcome()) {
            case SUCCESS -> AdminBindResult.Outcome.SUCCESS;
            case SUCCESS_REPLACED -> AdminBindResult.Outcome.SUCCESS_REPLACED;
            case ALREADY_BOUND -> AdminBindResult.Outcome.ALREADY_BOUND;
            case QQ_FULL -> AdminBindResult.Outcome.QQ_FULL;
            case PLAYER_FULL -> AdminBindResult.Outcome.PLAYER_FULL;
            case QQ_BANNED -> AdminBindResult.Outcome.QQ_BANNED;
            // adminBind 不走验证码/冷却，其余结局不可达——真出现就该大声失败
            default -> throw new AssertionError("adminBind 不应产生结局: " + r.outcome());
        };
        return adminBindResult(outcome, player, qq, r.evicted());
    }

    private AdminBindResult adminBindResult(AdminBindResult.Outcome outcome, String player, long qq,
                                             BindStore.Binding evicted) {
        return new AdminBindResult(outcome, player, qq,
                Optional.ofNullable(evicted).map(BindStore.Binding::name),
                binds.findByQq(qq).size(),
                binds.settings().maxPerQq,
                binds.settings().maxPerPlayer);
    }

    // ================= 状态计数 =================

    /**
     * 「状态」指令中 BindService 能回答的计数。
     * <p>连接状态（mode / connected / self_id / 连接时长）属于 OneBotEndpoint，
     * 是 QQ 侧独有信息，不进入本 record（见类注释"与 OneBotEndpoint 的边界"）。
     */
    public record StatusCounts(int bindings, int banned, int activeCodes) {
    }

    /** 绑定 / 黑名单 / 待验证码计数快照。 */
    public StatusCounts statusCounts() {
        return new StatusCounts(binds.allBindings().size(),
                binds.store().bannedQqs().size(),
                binds.activeCodeCount());
    }

    // ================= 内部工具 =================

    /** 按玩家名全量检索（忽略大小写），返回不可变列表。 */
    private List<BindStore.Binding> byName(String name) {
        return binds.allBindings().stream()
                .filter(b -> b.name().equalsIgnoreCase(name))
                .toList();
    }
}
