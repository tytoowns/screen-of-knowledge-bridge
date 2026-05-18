package com.tytoowns.osrstrackerbridge;

import net.runelite.api.Skill;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

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



    enum DisplayMode
    {
        FIXED_PROFILE,
        FIXED_PLAYER_AUTO_CATEGORY,
        FOLLOW_ACTIVE_SOURCE
    }

    enum GameModeChoice
    {
        NORMAL("normal", "hiscore_oldschool"),
        IRONMAN("ironman", "hiscore_oldschool_ironman"),
        HARDCORE_IRONMAN("hc_ironman", "hiscore_oldschool_hardcore_ironman"),
        ULTIMATE_IRONMAN("u_ironman", "hiscore_oldschool_ultimate"),
        GROUP_IRONMAN("group_ironman", "hiscore_oldschool"),
        GROUP_HARDCORE_IRONMAN("group_hc_ironman", "hiscore_oldschool"),
        GROUP_UNRANKED_IRONMAN("group_unranked_ironman", "hiscore_oldschool"),
        LEAGUE("league", "hiscore_oldschool_seasonal"),
        DEADMAN("deadman", "hiscore_oldschool_deadman"),
        TOURNAMENT("tournament", "hiscore_oldschool_tournament"),
        FRESH_START("fresh_start", "hiscore_oldschool_fresh_start");

        private final String gameModeKey;
        private final String endpoint;

        GameModeChoice(String gameModeKey, String endpoint)
        {
            this.gameModeKey = gameModeKey;
            this.endpoint = endpoint;
        }

        public String gameModeKey()
        {
            return gameModeKey;
        }

        public String endpoint()
        {
            return endpoint;
        }
    }

    @ConfigItem(
        keyName = "targetPlayerDisplay",
        name = "Target player",
        description = "Status only. Updated by the plugin.",
        position = 0,
        hidden = true
    )
    default String targetPlayerDisplay()
    {
        return "";
    }

    @ConfigItem(
        keyName = "targetHiscoreCategoryDisplay",
        name = "Target hiscore category",
        description = "Status only. Updated by the plugin.",
        position = 1,
        hidden = true
    )
    default String targetHiscoreCategoryDisplay()
    {
        return "";
    }

    @ConfigItem(
        keyName = "displayMode",
        name = "Display mode",
        description = "How the device chooses which profile to show.",
        position = 2,
        hidden = true
    )
    default DisplayMode displayMode()
    {
        return DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
    }

    @ConfigItem(
        keyName = "pinnedPlayer",
        name = "Pinned player",
        description = "Used by Fixed Profile and Fixed Player Auto-Switch modes.",
        position = 3,
        hidden = true
    )
    default String pinnedPlayer()
    {
        return "";
    }

    @ConfigItem(
        keyName = "pinnedGameMode",
        name = "Pinned game mode",
        description = "Used by Fixed Profile and API-only mode.",
        position = 4,
        hidden = true
    )
    default GameModeChoice pinnedGameMode()
    {
        return GameModeChoice.NORMAL;
    }

    @ConfigItem(
        keyName = "apiOnly",
        name = "API-only mode",
        description = "Ignore live plugin stats/toasts and use API + persisted data only.",
        position = 5,
        hidden = true
    )
    default boolean apiOnly()
    {
        return false;
    }


    @ConfigItem(
        keyName = "syncConfigNow",
        name = "Apply mode/profile now",
        description = "Pushes the current effective target/device mode config once, then auto-resets.",
        position = 6,
        hidden = true
    )
    default boolean syncConfigNow()
    {
        return false;
    }

    @ConfigItem(
        keyName = "setToLoggedInPlayerNow",
        name = "Set to currently logged in player",
        description = "Applies the currently logged-in player according to the active display mode, then pushes to the device. Auto-resets.",
        position = 7,
        hidden = true
    )
    default boolean setToLoggedInPlayerNow()
    {
        return false;
    }

    // “Button-like” toggles:
    @ConfigItem(
        keyName = "testPing",
        name = "Test connection (/ping)",
        description = "Toggle ON to call /ping once. It will auto-reset to OFF.",
        position = 5,
        hidden = true
    )
    default boolean testPing()
    {
        return false;
    }

    @ConfigItem(
        keyName = "testPush",
        name = "Send test push (toast)",
        description = "Toggle ON to send a test toast to the ESP32 once. It will auto-reset to OFF.",
        position = 6,
        hidden = true
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
        position = 6,
        hidden = true
    )
    default boolean toastTestSend()
    {
        return false;
    }


}
