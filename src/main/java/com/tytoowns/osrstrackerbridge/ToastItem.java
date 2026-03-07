package com.tytoowns.osrstrackerbridge;

public final class ToastItem
{
    public String kind;   // "MAX_TOTAL" | "LEVEL_99" | "LEVEL_UP" | "OTHER"
    public Integer prio;  // 100/80/40/10
    public Integer ms;    // duration override

    // Option B (structured fields)
    public String skill;  // e.g. "Slayer"
    public Integer level; // e.g. 26 (new level)
    public Integer total; // e.g. total level for MAX_TOTAL

    // Optional override. If omitted/empty, ESP32 formats from fields.
    public String msg;

    // Existing helper (keep for OTHER/test or when you want forced text)
    public static ToastItem of(String kind, int prio, int ms, String msg)
    {
        ToastItem t = new ToastItem();
        t.kind = kind;
        t.prio = prio;
        t.ms = ms;
        t.msg = msg;
        return t;
    }

    // New helper for structured toasts
    public static ToastItem structured(String kind, int prio, int ms, String skill, Integer level, Integer total)
    {
        ToastItem t = new ToastItem();
        t.kind = kind;
        t.prio = prio;
        t.ms = ms;
        t.skill = skill;
        t.level = level;
        t.total = total;
        // Leave msg null so Gson omits it (default behavior), forcing ESP32 formatting
        t.msg = null;
        return t;
    }
}

