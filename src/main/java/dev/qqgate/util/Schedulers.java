package dev.qqgate.util;

import org.bukkit.plugin.Plugin;

/**
 * Folia/Paper 兼容调度工具。
 *
 * 规则：Bukkit API 一律不在 WS 线程调用；
 * - 无区域依赖的游戏侧操作 → GlobalRegionScheduler
 * - 纯计算/IO → AsyncScheduler
 */
public final class Schedulers {

    private Schedulers() {
    }

    /** 全局区域线程执行（游戏侧安全）。 */
    public static void global(Plugin plugin, Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    /** 异步执行（IO/计算，禁止触碰 Bukkit 世界状态）。 */
    public static void async(Plugin plugin, Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
    }
}
