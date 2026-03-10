package com.tytoowns.osrstrackerbridge;

public final class PushPayload
{
    // Required
    public String player;

    // Metadata (robustness)
    public String event;     // "level_up" | "xp_tick" | "bank_update" | "snapshot" | "toast"
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

    // Explicit config update
    public String configPlayerName;
    public String hiscoreCategory;

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

    public static PushPayload bankUpdate(String player, long seq, long bankTotal)
    {
        PushPayload p = base(player, seq, "bank_update");
        p.bankTotal = bankTotal;
        return p;
    }

    public static PushPayload heartbeat(String player, long seq)
    {
        PushPayload p = base(player, seq, "heartbeat");
        p.heartbeat = Boolean.TRUE;
        return p;
    }

    public static PushPayload configUpdate(String player, long seq, String configPlayerName, String hiscoreCategory)
    {
        PushPayload p = base(player, seq, "config_update");
        p.configPlayerName = configPlayerName;
        p.hiscoreCategory = hiscoreCategory;
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

