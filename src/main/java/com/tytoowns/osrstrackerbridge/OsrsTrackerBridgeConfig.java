package com.tytoowns.osrstrackerbridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.api.Skill;
import net.runelite.client.config.*;


@ConfigGroup("osrstrackerbridge")
public interface OsrsTrackerBridgeConfig extends Config
{
    @ConfigItem(
        keyName = "deviceHost",
        name = "Device IP/Host",
        description = "Example: 192.168.1.50",
        position = 0
    )
    default String deviceHost()
    {
        return "";
    }

    @ConfigItem(
        keyName = "devicePort",
        name = "Device Port",
        description = "ESP32 HTTP port (default 8080)",
        position = 1
    )
    default int devicePort()
    {
        return 8080;
    }

    @ConfigItem(
        keyName = "sendLoginSnapshot",
        name = "Send snapshot on login",
        description = "Send all skill levels once when you log in (recommended).",
        position = 2
    )
    default boolean sendLoginSnapshot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableBankTotalPush",
        name = "Push bank total",
        description = "Push bank total value when opening/changing/closing your bank.",
        position = 3
    )
    default boolean enableBankTotalPush()
    {
        return true;
    }

    // “Button-like” toggles:
    @ConfigItem(
        keyName = "testPing",
        name = "Test connection (/ping)",
        description = "Toggle ON to call /ping once. It will auto-reset to OFF.",
        position = 4
    )
    default boolean testPing()
    {
        return false;
    }

    @ConfigItem(
        keyName = "testPush",
        name = "Send test push (toast)",
        description = "Toggle ON to send a test toast to the ESP32 once. It will auto-reset to OFF.",
        position = 5
    )
    default boolean testPush()
    {
        return false;
    }

    @ConfigSection(
        name = "Testing",
        description = "Tools for sending test toast payloads to the ESP32",
        position = 90
    )
    String testingSection = "testing";

    enum ToastTestPreset
    {
        NONE,

        SKILL_PLUS_ONE,        // +1 level up (pick skill)
        SKILL_99,              // 99 toast (pick skill)

        TOTAL_MILESTONE,       // milestone toast (pick milestone + total)

        LEVELUP_AND_99,        // order test
        LEVELUP_AND_MILESTONE,
        NINETY_NINE_AND_MILESTONE,
        NINETY_NINE_AND_MAX_TOTAL,

        FIVE_RANDOM_LEVEL_UPS,
        QUEUE_OVERFLOW,

        PREEMPT_LONG_WITH_99,
        PREEMPT_LONG_WITH_MILESTONE,

        MIXED_PRIORITY_ORDERING
    }

    enum SkillChoice
    {
        ATTACK(Skill.ATTACK),
        DEFENCE(Skill.DEFENCE),
        STRENGTH(Skill.STRENGTH),
        HITPOINTS(Skill.HITPOINTS),
        RANGED(Skill.RANGED),
        PRAYER(Skill.PRAYER),
        MAGIC(Skill.MAGIC),
        COOKING(Skill.COOKING),
        WOODCUTTING(Skill.WOODCUTTING),
        FLETCHING(Skill.FLETCHING),
        FISHING(Skill.FISHING),
        FIREMAKING(Skill.FIREMAKING),
        CRAFTING(Skill.CRAFTING),
        SMITHING(Skill.SMITHING),
        MINING(Skill.MINING),
        HERBLORE(Skill.HERBLORE),
        AGILITY(Skill.AGILITY),
        THIEVING(Skill.THIEVING),
        SLAYER(Skill.SLAYER),
        FARMING(Skill.FARMING),
        RUNECRAFT(Skill.RUNECRAFT),
        HUNTER(Skill.HUNTER),
        CONSTRUCTION(Skill.CONSTRUCTION),
        SAILING(Skill.SAILING);

        private final Skill skill;
        SkillChoice(Skill s) { this.skill = s; }
        public Skill skill() { return skill; }
    }

    @ConfigItem(
        keyName = "toastTestPreset",
        name = "Toast test preset",
        description = "Select a toast test scenario",
        section = testingSection,
        position = 0
    )
    default ToastTestPreset toastTestPreset()
    {
        return ToastTestPreset.NONE;
    }

    @ConfigItem(
        keyName = "toastTestSkill1",
        name = "Skill (primary)",
        description = "Skill used by presets that need a skill",
        section = testingSection,
        position = 1
    )
    default SkillChoice toastTestSkill1()
    {
        return SkillChoice.ATTACK;
    }

    @ConfigItem(
        keyName = "toastTestSkill2",
        name = "Skill (secondary)",
        description = "Second skill used by combo / preemption presets",
        section = testingSection,
        position = 2
    )
    default SkillChoice toastTestSkill2()
    {
        return SkillChoice.AGILITY;
    }

    @Range(min = 1, max = 2475)
    @ConfigItem(
        keyName = "toastTestMilestone",
        name = "Total milestone",
        description = "Milestone threshold for TOTAL_LEVEL toast",
        section = testingSection,
        position = 3
    )
    default int toastTestMilestone()
    {
        return 1000;
    }

    @Range(min = 1, max = 2475)
    @ConfigItem(
        keyName = "toastTestTotalLevel",
        name = "Total level (actual)",
        description = "Actual total level value (can be >= milestone)",
        section = testingSection,
        position = 4
    )
    default int toastTestTotalLevel()
    {
        return 1000;
    }

    @Range(min = 1, max = 24)
    @ConfigItem(
        keyName = "toastTestCount",
        name = "Count",
        description = "Used by presets that generate N toasts (e.g. random level-ups)",
        section = testingSection,
        position = 5
    )
    default int toastTestCount()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "toastTestSend",
        name = "Send test",
        description = "Click to send the selected toast test preset",
        section = testingSection,
        position = 6
    )
    default boolean toastTestSend()
    {
        return false;
    }


}
