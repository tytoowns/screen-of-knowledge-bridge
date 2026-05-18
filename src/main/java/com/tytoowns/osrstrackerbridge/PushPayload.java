package com.tytoowns.osrstrackerbridge;

public final class PushPayload
{
    // Required
    public String player;

    // Metadata (robustness)
    public String event;     // "level_up" | "xp_tick" | "bank_update" | "snapshot" | "heartbeat" | "config_update" | "toast" | "toast_batch"
    public Long seq;         // monotonic counter (per plugin runtime)
    public long ts;          // epoch ms

    // Skill delta
    public Integer id;
    public Integer level;

    // Snapshot levels (len 25, index 0 = total level)
    public int[] levels;

    // Totals
    public Integer totalLevel;
    public Long totalXp;

    // Bank total (gp)
    public Long bankTotal;

    // Heartbeat
    public Boolean heartbeat;

    // Explicit config update / profile identity
    public String configPlayerName;
    public String hiscoreCategory;
    public String gameMode;

    public String displayMode;
    public String pinnedPlayer;
    public String pinnedGameMode;
    public String pinnedCategory;
    public Boolean apiOnly;
    // Toast/test
    public String toast;
    public Integer toastMs;
    // toast batch
    public Boolean flushLower;
    public ToastItem[] toasts;


    private static PushPayload base(String player, long seq, String event)
    {
        PushPayload p = new PushPayload();
        p.player = player;
        p.seq = seq;
        p.event = event;
        p.ts = System.currentTimeMillis();
        return p;
    }

    public static PushPayload levelUpDelta(String player, long seq, int id, int level, int totalLevel, long totalXp)
    {
        PushPayload p = base(player, seq, "level_up");
        p.id = id;
        p.level = level;
        p.totalLevel = totalLevel;
        p.totalXp = totalXp;
        return p;
    }

    public static PushPayload xpTick(String player, long seq, long totalXp, Integer totalLevelOptional)
    {
        PushPayload p = base(player, seq, "xp_tick");
        p.totalXp = totalXp;
        p.totalLevel = totalLevelOptional; // nullable
        return p;
    }

    public static PushPayload bankUpdate(String player, long seq, long bankTotal, String hiscoreCategory)
    {
        PushPayload p = base(player, seq, "bank_update");
        p.bankTotal = bankTotal;
        p.hiscoreCategory = hiscoreCategory;
        return p;
    }

    public static PushPayload heartbeat(String player, long seq)
    {
        PushPayload p = base(player, seq, "heartbeat");
        p.heartbeat = Boolean.TRUE;
        return p;
    }

    public static PushPayload configUpdate(
        String player,
        long seq,
        String configPlayerName,
        String hiscoreCategory,
        String gameMode,
        String displayMode,
        String pinnedPlayer,
        String pinnedGameMode,
        String pinnedCategory,
        boolean apiOnly
    )
    {
        PushPayload p = base(player, seq, "config_update");
        p.configPlayerName = configPlayerName;
        p.hiscoreCategory = hiscoreCategory;
        p.gameMode = gameMode;
        p.displayMode = displayMode;
        p.pinnedPlayer = pinnedPlayer;
        p.pinnedGameMode = pinnedGameMode;
        p.pinnedCategory = pinnedCategory;
        p.apiOnly = apiOnly ? Boolean.TRUE : Boolean.FALSE;
        return p;
    }

    public static PushPayload sourceAnnounce(String player, long seq, String hiscoreCategory, String gameMode)
    {
        PushPayload p = base(player, seq, "source_announce");
        p.hiscoreCategory = hiscoreCategory;
        p.gameMode = gameMode;
        return p;
    }

    public static PushPayload snapshot(String player, long seq, int[] levels25, int totalLevel, long totalXp)
    {
        PushPayload p = base(player, seq, "snapshot");
        p.levels = levels25;
        p.totalLevel = totalLevel;
        p.totalXp = totalXp;
        return p;
    }

    public static PushPayload toast(String player, long seq, String toast, int toastMs)
    {
        PushPayload p = base(player, seq, "toast");
        p.toast = toast;
        p.toastMs = toastMs;
        return p;
    }

    public static PushPayload toastBatch(String player, long seq, boolean flushLower, ToastItem[] toasts,
                                         Integer totalLevelOptional, Long totalXpOptional)
    {
        PushPayload p = base(player, seq, "toast_batch");
        p.flushLower = flushLower ? Boolean.TRUE : Boolean.FALSE;
        p.toasts = toasts;
        p.totalLevel = totalLevelOptional;
        p.totalXp = totalXpOptional;
        return p;
    }

}

