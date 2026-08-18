package dev.qqgate.bind;

/**
 * 绑定行为参数（纯 POJO，从 config.yml 提取，可单测）。
 */
public final class BindSettings {

    public enum LimitPolicy { REJECT, REPLACE }

    public enum ExpireDisplay { BOTH, RELATIVE, ABSOLUTE }

    public final int codeLength;
    public final long expireMillis;
    public final ExpireDisplay expireDisplay;
    public final int maxPerQq;
    public final int maxPerPlayer;
    public final LimitPolicy limitPolicy;
    public final boolean selfUnbind;
    public final int cooldownSeconds;
    public final boolean refreshOnRejoin;

    public BindSettings(int codeLength, long expireMillis, ExpireDisplay expireDisplay,
                        int maxPerQq, int maxPerPlayer, LimitPolicy limitPolicy,
                        boolean selfUnbind, int cooldownSeconds, boolean refreshOnRejoin) {
        this.codeLength = clamp(codeLength, 4, 8);
        this.expireMillis = Math.max(60_000L, expireMillis);
        this.expireDisplay = expireDisplay;
        this.maxPerQq = Math.max(1, maxPerQq);
        this.maxPerPlayer = Math.max(1, maxPerPlayer);
        this.limitPolicy = limitPolicy;
        this.selfUnbind = selfUnbind;
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.refreshOnRejoin = refreshOnRejoin;
    }

    public static BindSettings defaults() {
        return new BindSettings(4, 5 * 60_000L, ExpireDisplay.BOTH,
                1, 1, LimitPolicy.REJECT, false, 10, true);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 测试友好构造器：默认值起点，按需覆盖。 */
    public static final class Builder {
        private int codeLength = 4;
        private long expireMillis = 5 * 60_000L;
        private ExpireDisplay expireDisplay = ExpireDisplay.BOTH;
        private int maxPerQq = 1;
        private int maxPerPlayer = 1;
        private LimitPolicy limitPolicy = LimitPolicy.REJECT;
        private boolean selfUnbind = false;
        private int cooldownSeconds = 10;
        private boolean refreshOnRejoin = true;

        public Builder codeLength(int v) { this.codeLength = v; return this; }
        public Builder expireMillis(long v) { this.expireMillis = v; return this; }
        public Builder expireDisplay(ExpireDisplay v) { this.expireDisplay = v; return this; }
        public Builder maxPerQq(int v) { this.maxPerQq = v; return this; }
        public Builder maxPerPlayer(int v) { this.maxPerPlayer = v; return this; }
        public Builder limitPolicy(LimitPolicy v) { this.limitPolicy = v; return this; }
        public Builder selfUnbind(boolean v) { this.selfUnbind = v; return this; }
        public Builder cooldownSeconds(int v) { this.cooldownSeconds = v; return this; }
        public Builder refreshOnRejoin(boolean v) { this.refreshOnRejoin = v; return this; }

        public BindSettings build() {
            return new BindSettings(codeLength, expireMillis, expireDisplay, maxPerQq,
                    maxPerPlayer, limitPolicy, selfUnbind, cooldownSeconds, refreshOnRejoin);
        }
    }
}
