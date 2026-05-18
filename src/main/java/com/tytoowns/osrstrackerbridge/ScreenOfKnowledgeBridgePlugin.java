package com.tytoowns.osrstrackerbridge;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.regex.Pattern;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.events.WorldChanged;


@PluginDescriptor(
    name = "Screen Of Knowledge Bridge",
        description = "Pushes skill updates + bank total + toast milestones to the SoK (Screen Of Knowledge) on your LAN"

)
public class ScreenOfKnowledgeBridgePlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(ScreenOfKnowledgeBridgePlugin.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private volatile String cachedPlayerName = null;
    private NavigationButton navButton;
    private ScreenOfKnowledgeBridgePanel sidePanel;
    @Inject private ClientToolbar clientToolbar;

    private static final String CONFIG_GROUP = "osrstrackerbridge";
    private static final String CONFIG_KEY_TARGET_PLAYER_DISPLAY = "targetPlayerDisplay";
    private static final String CONFIG_KEY_TARGET_HISCORE_CATEGORY_DISPLAY = "targetHiscoreCategoryDisplay";

    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";
    private static final String CONFIG_KEY_PINNED_PLAYER = "pinnedPlayer";
    private static final String CONFIG_KEY_PINNED_GAME_MODE = "pinnedGameMode";
    private static final String CONFIG_KEY_API_ONLY = "apiOnly";

    private static final String CONFIG_KEY_SYNC_CONFIG_NOW = "syncConfigNow";
    private static final String CONFIG_KEY_SET_TO_LOGGED_IN_PLAYER_NOW = "setToLoggedInPlayerNow";
    private static final String CONFIG_KEY_LAST_BANK_TOTAL_PREFIX = "lastKnownBankTotal.";
    private static final String BANK_KEY_SEP = "|";

    // Timings / policy (existing)
    private static final long XP_TICK_MS = 5_000;
    private static final long HEARTBEAT_MS = 5_000;
    private static final long DEVICE_STATUS_POLL_MS = 5_000;
    private static final long SNAPSHOT_EVERY_MS = 180_000;
    private static final long BANK_DEBOUNCE_MS = 650;
    private static final int  INCLUDE_TOTAL_LEVEL_EVERY_N_XP_TICKS = 12;

    private static final String[] FIXED_PLAYER_AUTO_CATEGORY_PROBE_ORDER = new String[] {
        "hiscore_oldschool",
        "hiscore_oldschool_ironman",
        "hiscore_oldschool_hardcore_ironman",
        "hiscore_oldschool_ultimate",
        "hiscore_oldschool_seasonal",
        "hiscore_oldschool_deadman",
        "hiscore_oldschool_tournament",
        "hiscore_oldschool_fresh_start"
    };

    private void updateTargetDisplay(String player, String hiscoreCategory)
    {
        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_TARGET_PLAYER_DISPLAY,
            player == null ? "" : player
        );

        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_TARGET_HISCORE_CATEGORY_DISPLAY,
            hiscoreCategory == null ? "" : hiscoreCategory
        );
    }


        // --------------------
    // Toast aggregation (NEW)
    // --------------------
    private static final long TOAST_AGG_WINDOW_MS = 350; // 250–500ms window
    private static final int  TOAST_LEVELUP_CAP   = 5;

    // Total level milestone toasts
    private static final int[] TOTAL_LEVEL_MILESTONES = new int[] { 100, 500, 1000, 1500, 2000 };

    // In-flight aggregated milestone hit (keep the highest crossed in the window)
    private int toastAggTotalMilestone = 0;        // e.g. 1000
    private int toastAggTotalAtMilestone = 0;      // actual total level when observed (can be > milestone)


    // Suppress toast aggregation briefly after login/world-hop to avoid bogus toasts
    private static final long LOGIN_TOAST_SUPPRESS_MS = 5000; // 3–8s is typical
    private volatile long toastSuppressUntilMs = 0;

    private void clearToastAggState()
    {
        synchronized (toastAggLock)
        {
            if (toastAggFuture != null)
            {
                toastAggFuture.cancel(false);
                toastAggFuture = null;
            }
            toastAggEvents.clear();
            toastAggHit99.clear();
            toastAggSawMaxTotal = false;

            toastAggTotalMilestone = 0;
            toastAggTotalAtMilestone = 0;
        }
    }



    private final Object toastAggLock = new Object();
    private ScheduledFuture<?> toastAggFuture;

    // Keep insertion order for stable FIFO toast ordering
    private final java.util.LinkedHashMap<Skill, ToastAggEvent> toastAggEvents = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashSet<String> toastAggHit99 = new java.util.LinkedHashSet<>();
    private boolean toastAggSawMaxTotal = false;

    private static final class ToastAggEvent
    {
        final Skill skill;
        final int id;
        int from;
        int to;

        ToastAggEvent(Skill skill, int id, int from, int to)
        {
            this.skill = skill;
            this.id = id;
            this.from = from;
            this.to = to;
        }
    }


    // Sender retry/backoff (existing)
    private static final long RETRY_BACKOFF_BASE_MS = 250;
    private static final long RETRY_BACKOFF_MAX_MS  = 5_000;

    private static final Map<Skill, Integer> SKILL_TO_ID = ImmutableMap.<Skill, Integer>builder()
        .put(Skill.ATTACK, 1)
        .put(Skill.DEFENCE, 2)
        .put(Skill.STRENGTH, 3)
        .put(Skill.HITPOINTS, 4)
        .put(Skill.RANGED, 5)
        .put(Skill.PRAYER, 6)
        .put(Skill.MAGIC, 7)
        .put(Skill.COOKING, 8)
        .put(Skill.WOODCUTTING, 9)
        .put(Skill.FLETCHING, 10)
        .put(Skill.FISHING, 11)
        .put(Skill.FIREMAKING, 12)
        .put(Skill.CRAFTING, 13)
        .put(Skill.SMITHING, 14)
        .put(Skill.MINING, 15)
        .put(Skill.HERBLORE, 16)
        .put(Skill.AGILITY, 17)
        .put(Skill.THIEVING, 18)
        .put(Skill.SLAYER, 19)
        .put(Skill.FARMING, 20)
        .put(Skill.RUNECRAFT, 21)
        .put(Skill.HUNTER, 22)
        .put(Skill.CONSTRUCTION, 23)
        .put(Skill.SAILING, 24)
        .build();

    private enum PendingKind
    {
        CONFIG,
        TOAST,
        LEVEL,
        BANK,
        XP,
        HEARTBEAT,
        SNAPSHOT
    }


    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OsrsTrackerBridgeConfig config;
    @Inject private Gson gson;
    @Inject private ConfigManager configManager;
    @Inject private ItemManager itemManager;
    @Inject private ScheduledExecutorService executor;

    @Inject
    private OkHttpClient http;

    // --------------------
    // State caches (skills/bank)
    // --------------------
    private final EnumMap<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);

    private volatile long lastKnownTotalXp = -1;
    private volatile int  lastKnownTotalLevel = -1;

    private volatile long lastSentTotalXp = -1;
    private volatile long lastSentBankTotal = -1;

    private boolean bankOpen = false;

    // --------------------
    // Schedulers (skills/bank)
    // --------------------
    private ScheduledFuture<?> xpTickFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> snapshotFuture;
    private ScheduledFuture<?> bankDebounceFuture;
    private ScheduledFuture<?> deviceStatusFuture;

    private volatile String cachedDeviceDisplayMode = null;
    private volatile String cachedDevicePinnedPlayer = null;
    private volatile String cachedDevicePinnedGameMode = null;
    private volatile String cachedDevicePinnedCategory = null;
    private volatile Boolean cachedDeviceApiOnly = null;

    private volatile String cachedDeviceTargetPlayer = null;
    private volatile String cachedDeviceTargetGameMode = null;
    private volatile String cachedDeviceTargetCategory = null;

    private volatile String cachedDeviceVisiblePlayer = null;
    private volatile String cachedDeviceVisibleGameMode = null;
    private volatile String cachedDeviceVisibleCategory = null;
    private volatile Boolean cachedDevicePluginHeartbeatFresh = null;
    private volatile Boolean cachedDevicePluginDisplayAllowed = null;
    private volatile String cachedDeviceLiveOwnerPlayer = null;
    private volatile String cachedDeviceLiveOwnerGameMode = null;
    private volatile String cachedDeviceLiveOwnerCategory = null;
    private volatile boolean followBootstrapSentForCurrentOwnership = false;

    private volatile String lastDeviceSettingsSnapshot = null;

    private volatile String cachedCurrentPlayerName = "-";
    private volatile String cachedCurrentHiscoreCategoryDisplay = "-";
    private volatile String cachedCurrentModeDisplay = "-";

    private long xpTickCounter = 0;

    // --------------------
    // Coalescing sender (skills/bank)
    // --------------------
    private final Object sendLock = new Object();
    private final EnumMap<PendingKind, PushPayload> pending = new EnumMap<>(PendingKind.class);

    private PendingKind inFlightKind;
    private PushPayload inFlightPayload;

    private ScheduledFuture<?> retryFuture;
    private long backoffMs = RETRY_BACKOFF_BASE_MS;
    private int consecutiveFailures = 0;

    private long seq = 0;

    @Provides
    OsrsTrackerBridgeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsTrackerBridgeConfig.class);
    }



    @Override
    protected void startUp()
    {
        seedLastLevelsFromClient();

        bankOpen = false;
        lastKnownTotalXp = -1;
        lastKnownTotalLevel = -1;
        lastSentTotalXp = -1;
        lastSentBankTotal = -1;


        xpTickCounter = 0;

        cancelFutures();
        clearSenderState();
        lastDeviceSettingsSnapshot = null;

        sidePanel = new ScreenOfKnowledgeBridgePanel(this);

        BufferedImage icon = loadNavIcon();

        navButton = NavigationButton.builder()
            .tooltip("Screen Of Knowledge Bridge")
            .icon(icon)
            .priority(6)
            .panel(sidePanel)
            .build();

        clientToolbar.addNavigation(navButton);

        // If plugin starts while already logged in, do the same “logged in” init path.
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            // Prevent immediate stat sync from firing toasts
            toastSuppressUntilMs = System.currentTimeMillis() + LOGIN_TOAST_SUPPRESS_MS;
            clearToastAggState();

            clientThread.invokeLater(() ->
            {
                seedLastLevelsFromClient();
                refreshTotalsCacheFromClient();
                refreshCurrentProfileInfoCache();
                refreshTargetDisplay();

                String loggedIn = getPushPlayer();
                String category = resolveTargetHiscoreCategoryFromMode();
                Long savedBankTotal = loadLastKnownBankTotalForProfile(loggedIn, category);
                if (savedBankTotal != null)
                {
                    lastSentBankTotal = savedBankTotal;
                }
                else
                {
                    lastSentBankTotal = -1;
                }

                pollDeviceStatusOnce();
                startDeviceStatusTicker();

                // Startup can also see stale world-type state for a moment.
                // Re-announce after a short delay so FIXED_PLAYER_AUTO_CATEGORY / FOLLOW_ACTIVE_SOURCE
                // resolve the correct auto category without sending a manual config update.
                scheduleDelayedAutoSourceAnnounce(600);
                scheduleDelayedAutoSourceAnnounce(1500);

                executor.schedule(() -> clientThread.invokeLater(() ->
                {
                    startXpTicker();
                    startHeartbeatTicker();
                    startSnapshotTicker();

                    if (config.sendLoginSnapshot())
                    {
                        enqueueSnapshotNow("startup");
                        enqueueLastKnownBankNow();
                    }
                }), 300, TimeUnit.MILLISECONDS);
            });
        }
    }

    @Override
    protected void shutDown()
    {

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        sidePanel = null;

        cancelFutures();
        lastLevels.clear();
        clearSenderState();
        bankOpen = false;
        setCachedDeviceTarget(null, null);
        cachedDeviceDisplayMode = null;
        cachedDevicePinnedPlayer = null;
        cachedDevicePinnedGameMode = null;
        cachedDevicePinnedCategory = null;
        cachedDeviceApiOnly = null;
        cachedDeviceVisiblePlayer = null;
        cachedDeviceVisibleGameMode = null;
        cachedDeviceVisibleCategory = null;
        cachedDevicePluginHeartbeatFresh = null;
        cachedDevicePluginDisplayAllowed = null;
        cachedDeviceLiveOwnerPlayer = null;
        cachedDeviceLiveOwnerGameMode = null;
        cachedDeviceLiveOwnerCategory = null;
        followBootstrapSentForCurrentOwnership = false;
        lastDeviceSettingsSnapshot = null;
    }

    private void cancelFutures()
    {
        if (xpTickFuture != null) { xpTickFuture.cancel(false); xpTickFuture = null; }
        if (heartbeatFuture != null) { heartbeatFuture.cancel(false); heartbeatFuture = null; }
        if (snapshotFuture != null) { snapshotFuture.cancel(false); snapshotFuture = null; }
        if (bankDebounceFuture != null) { bankDebounceFuture.cancel(false); bankDebounceFuture = null; }
        if (deviceStatusFuture != null) { deviceStatusFuture.cancel(false); deviceStatusFuture = null; }
    }

    private void clearSenderState()
    {
        synchronized (sendLock)
        {
            pending.clear();
            inFlightKind = null;
            inFlightPayload = null;

            if (retryFuture != null)
            {
                retryFuture.cancel(false);
                retryFuture = null;
            }

            backoffMs = RETRY_BACKOFF_BASE_MS;
            consecutiveFailures = 0;

        }
    }

    private BufferedImage loadNavIcon()
    {
        try
        {
            java.io.InputStream in =
                ScreenOfKnowledgeBridgePlugin.class.getResourceAsStream("sok_icon.png");

            if (in == null)
            {
                log.warn("Side panel icon resource not found: sok_icon.png");
                return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            }

            BufferedImage image = ImageIO.read(in);
            if (image != null)
            {
                return image;
            }

            log.warn("Side panel icon failed to decode: sok_icon.png");
        }
        catch (IOException | IllegalArgumentException ex)
        {
            log.warn("Failed to load side panel icon: {}", ex.toString());
        }

        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    void uiApplyModeProfileNow()
    {
        uiSetPanelStatus("Applying mode/profile to device...");

        clientThread.invokeLater(() ->
        {
            refreshTargetDisplay();
            pushConfigNowValidated();
        });
    }

    void uiSetToLoggedInPlayerNow()
    {
        uiSetPanelStatus("Applying logged-in player for current mode...");

        clientThread.invokeLater(() ->
        {
            String loggedIn = getPlayerName();
            if (loggedIn == null || loggedIn.trim().isEmpty())
            {
                refreshTargetDisplay();
                uiSetPanelStatus("No logged-in player found.");
                return;
            }

            String loggedInTrimmed = loggedIn.trim();

            boolean apiOnly = cachedDeviceApiOnly != null ? cachedDeviceApiOnly : config.apiOnly();

            OsrsTrackerBridgeConfig.DisplayMode mode;
            if (cachedDeviceDisplayMode != null && !cachedDeviceDisplayMode.trim().isEmpty())
            {
                try
                {
                    mode = OsrsTrackerBridgeConfig.DisplayMode.valueOf(cachedDeviceDisplayMode.trim());
                }
                catch (IllegalArgumentException ex)
                {
                    mode = config.displayMode();
                }
            }
            else
            {
                mode = config.displayMode();
            }

            if (mode == null)
            {
                mode = OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
            }

            configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_API_ONLY, apiOnly);
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_DISPLAY_MODE, mode.name());

            if (apiOnly)
            {
                configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PINNED_PLAYER, loggedInTrimmed);

                String deviceGameMode = deriveDeviceGameMode();
                OsrsTrackerBridgeConfig.GameModeChoice mappedChoice =
                    mapGameModeKeyToChoice(deviceGameMode);

                configManager.setConfiguration(
                    CONFIG_GROUP,
                    CONFIG_KEY_PINNED_GAME_MODE,
                    mappedChoice.name()
                );
            }
            else
            {
                switch (mode)
                {
                    case FIXED_PROFILE:
                    {
                        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PINNED_PLAYER, loggedInTrimmed);

                        String deviceGameMode = deriveDeviceGameMode();
                        OsrsTrackerBridgeConfig.GameModeChoice mappedChoice =
                            mapGameModeKeyToChoice(deviceGameMode);

                        configManager.setConfiguration(
                            CONFIG_GROUP,
                            CONFIG_KEY_PINNED_GAME_MODE,
                            mappedChoice.name()
                        );
                        break;
                    }

                    case FIXED_PLAYER_AUTO_CATEGORY:
                    {
                        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PINNED_PLAYER, loggedInTrimmed);
                        break;
                    }

                    case FOLLOW_ACTIVE_SOURCE:
                    {
                        // keep mode exactly as device reported; do not force pinned fields
                        break;
                    }

                    default:
                    {
                        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PINNED_PLAYER, loggedInTrimmed);
                        break;
                    }
                }
            }

            refreshTargetDisplay();

            if (sidePanel != null)
            {
                sidePanel.refreshFromPlugin();
            }

            pushConfigNowValidated();
        });
    }

    void uiTestPing()
    {
        doPing();
    }

    void uiSendTestPush()
    {
        uiSetPanelStatus("Sending test push...");
        sendTestToast();
    }

    void uiSendSelectedToastTest()
    {
        uiSetPanelStatus("Sending selected toast test...");
        runToastTestPreset();
    }





        void uiSetDisplayMode(OsrsTrackerBridgeConfig.DisplayMode mode)
    {
        if (mode == null)
        {
            mode = OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
        }

        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_DISPLAY_MODE,
            mode.name()
        );
    }

    void uiSetPinnedPlayer(String value)
    {
        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_PINNED_PLAYER,
            value == null ? "" : value.trim()
        );
    }

    void uiSetPinnedGameMode(OsrsTrackerBridgeConfig.GameModeChoice choice)
    {
        if (choice == null)
        {
            choice = OsrsTrackerBridgeConfig.GameModeChoice.NORMAL;
        }

        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_PINNED_GAME_MODE,
            choice.name()
        );
    }

    void uiSetApiOnly(boolean value)
    {
        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_API_ONLY,
            value
        );
    }

    OsrsTrackerBridgeConfig.DisplayMode uiGetDisplayMode()
    {
        OsrsTrackerBridgeConfig.DisplayMode mode = config.displayMode();
        return mode == null ? OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY : mode;
    }

    String uiGetPinnedPlayer()
    {
        String value = config.pinnedPlayer();
        return value == null ? "" : value;
    }

    OsrsTrackerBridgeConfig.GameModeChoice uiGetPinnedGameMode()
    {
        OsrsTrackerBridgeConfig.GameModeChoice choice = config.pinnedGameMode();
        return choice == null ? OsrsTrackerBridgeConfig.GameModeChoice.NORMAL : choice;
    }

    boolean uiIsApiOnly()
    {
        return config.apiOnly();
    }

    String uiGetDeviceDisplayMode()
    {
        return cachedDeviceDisplayMode;
    }

    String uiGetDeviceTargetPlayer()
    {
        return cachedDeviceTargetPlayer;
    }

    String uiGetDeviceTargetCategory()
    {
        return cachedDeviceTargetCategory;
    }

    String uiGetDeviceVisiblePlayer()
    {
        return cachedDeviceVisiblePlayer;
    }

    String uiGetDeviceVisibleCategory()
    {
        return cachedDeviceVisibleCategory;
    }

    Boolean uiGetDevicePluginHeartbeatFresh()
    {
        return cachedDevicePluginHeartbeatFresh;
    }

    Boolean uiGetDevicePluginDisplayAllowed()
    {
        return cachedDevicePluginDisplayAllowed;
    }

    String uiGetDeviceLiveOwnerPlayer()
    {
        return cachedDeviceLiveOwnerPlayer;
    }

    String uiGetDeviceLiveOwnerGameMode()
    {
        return cachedDeviceLiveOwnerGameMode;
    }

    String uiGetDeviceLiveOwnerCategory()
    {
        return cachedDeviceLiveOwnerCategory;
    }

    void uiSetPanelStatus(String text)
    {
        if (sidePanel != null)
        {
            sidePanel.setStatusText(text);
        }
    }

    String uiGetCurrentPlayerName()
    {
        return cachedCurrentPlayerName;
    }

    String uiGetCurrentHiscoreCategoryDisplay()
    {
        return cachedCurrentHiscoreCategoryDisplay;
    }

    String uiGetCurrentModeDisplay()
    {
        return cachedCurrentModeDisplay;
    }

    // ---- config toggles ----
    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"osrstrackerbridge".equals(e.getGroup()))
        {
            return;
        }


        if (CONFIG_KEY_TARGET_PLAYER_DISPLAY.equals(e.getKey())
            || CONFIG_KEY_TARGET_HISCORE_CATEGORY_DISPLAY.equals(e.getKey()))
        {
            return;
        }

        if ("testPing".equals(e.getKey()) && "true".equals(e.getNewValue()))
        {
            uiTestPing();
            configManager.setConfiguration("osrstrackerbridge", "testPing", false);
        }
        else if ("testPush".equals(e.getKey()) && "true".equals(e.getNewValue()))
        {
            uiSendTestPush();
            configManager.setConfiguration("osrstrackerbridge", "testPush", false);
        }
        else if ("toastTestSend".equals(e.getKey()) && "true".equals(e.getNewValue()))
        {
            uiSendSelectedToastTest();
            configManager.setConfiguration("osrstrackerbridge", "toastTestSend", false);
        }
        else if (CONFIG_KEY_DISPLAY_MODE.equals(e.getKey())
            || CONFIG_KEY_PINNED_PLAYER.equals(e.getKey())
            || CONFIG_KEY_PINNED_GAME_MODE.equals(e.getKey())
            || CONFIG_KEY_API_ONLY.equals(e.getKey()))
        {
            scheduleTargetRefreshAndConfigSync(false);
        }
        else if (CONFIG_KEY_SET_TO_LOGGED_IN_PLAYER_NOW.equals(e.getKey()) && "true".equals(e.getNewValue()))
        {
            uiSetToLoggedInPlayerNow();
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SET_TO_LOGGED_IN_PLAYER_NOW, false);
        }
        else if (CONFIG_KEY_SYNC_CONFIG_NOW.equals(e.getKey()) && "true".equals(e.getNewValue()))
        {
            uiApplyModeProfileNow();
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SYNC_CONFIG_NOW, false);
        }
    }

    private void runToastTestPreset()
    {
        OsrsTrackerBridgeConfig.ToastTestPreset preset = config.toastTestPreset();
        if (preset == null || preset == OsrsTrackerBridgeConfig.ToastTestPreset.NONE)
        {
            return;
        }

        final Skill s1 = config.toastTestSkill1().skill();
        final Skill s2 = config.toastTestSkill2().skill();

        final String n1 = skillName(s1);
        final String n2 = skillName(s2);

        final int milestone = clamp(config.toastTestMilestone(), 1, MAX_TOTAL_LEVEL);
        final int totalActual = clamp(config.toastTestTotalLevel(), 1, MAX_TOTAL_LEVEL);

        final int count = clamp(config.toastTestCount(), 1, 24);

        switch (preset)
        {
            case SKILL_PLUS_ONE:
            {
                int base = (client.getGameState() == GameState.LOGGED_IN)
                    ? client.getRealSkillLevel(s1)
                    : 1;
                int to = Math.min(99, base + 1);

                sendToastBatch(false, new ToastItem[] {
                    ToastItem.structured("LEVEL_UP", 40, 6000, n1, to, null)
                });
                break;
            }

            case SKILL_99:
            {
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_99", 80, 10000, n1, 99, null)
                });
                break;
            }

            case TOTAL_MILESTONE:
            {
                int actual = Math.max(totalActual, milestone);
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, milestone, actual)
                });
                break;
            }

            case LEVELUP_AND_99:
            {
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_UP", 40, 6000, n1, 50, null),
                    ToastItem.structured("LEVEL_99", 80, 10000, n2, 99, null)
                });
                break;
            }

            case LEVELUP_AND_MILESTONE:
            {
                int actual = Math.max(totalActual, milestone);
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_UP", 40, 6000, n1, 50, null),
                    ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, milestone, actual)
                });
                break;
            }

            case NINETY_NINE_AND_MILESTONE:
            {
                int actual = Math.max(totalActual, milestone);
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_99", 80, 10000, n1, 99, null),
                    ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, milestone, actual)
                });
                break;
            }

            case NINETY_NINE_AND_MAX_TOTAL:
            {
                // ESP32 “MAX_TOTAL collapses all” logic should win; this tests that rule.
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_99", 80, 10000, n1, 99, null),
                    ToastItem.structured("MAX_TOTAL", 100, 10000, null, null, MAX_TOTAL_LEVEL)
                });
                break;
            }

            case FIVE_RANDOM_LEVEL_UPS:
            {
                java.util.Random rng = new java.util.Random(System.currentTimeMillis());
                Skill[] skills = SKILL_TO_ID.keySet().toArray(new Skill[0]);

                java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++)
                {
                    Skill s = skills[rng.nextInt(skills.length)];
                    String name = skillName(s);
                    int lvl = 2 + rng.nextInt(98); // 2..99
                    out.add(ToastItem.structured("LEVEL_UP", 40, 6000, name, lvl, null));
                }

                sendToastBatch(false, out.toArray(new ToastItem[0]));
                break;
            }

            case QUEUE_OVERFLOW:
            {
                java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(20);
                for (int i = 1; i <= 20; i++)
                {
                    out.add(ToastItem.of("OTHER", 10, 1500, "QUEUE TEST " + i));
                }
                sendToastBatch(false, out.toArray(new ToastItem[0]));
                break;
            }

            case PREEMPT_LONG_WITH_99:
            {
                sendToastBatch(false, new ToastItem[] {
                    ToastItem.of("OTHER", 10, 10000, "LOW PRIO LONG TOAST (should be preempted)")
                });

                scheduleClientToast(500, () -> sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("LEVEL_99", 80, 10000, n1, 99, null)
                }));
                break;
            }

            case PREEMPT_LONG_WITH_MILESTONE:
            {
                int actual = Math.max(totalActual, milestone);

                sendToastBatch(false, new ToastItem[] {
                    ToastItem.of("OTHER", 10, 10000, "LOW PRIO LONG TOAST (should be preempted)")
                });

                scheduleClientToast(500, () -> sendToastBatch(true, new ToastItem[] {
                    ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, milestone, actual)
                }));
                break;
            }

            case MIXED_PRIORITY_ORDERING:
            {
                // One batch with low/med/high prio to validate ordering and ESP32 formatting.
                sendToastBatch(true, new ToastItem[] {
                    ToastItem.of("OTHER", 10, 6000, "LOW"),
                    ToastItem.structured("LEVEL_UP", 40, 6000, n1, 42, null),
                    ToastItem.structured("LEVEL_99", 80, 10000, n2, 99, null)
                });
                break;
            }

            default:
                break;
        }
    }

    private static int clamp(int v, int lo, int hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }


    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGGED_IN)
        {
            // Prevent login stat sync from generating toast batches
            toastSuppressUntilMs = System.currentTimeMillis() + LOGIN_TOAST_SUPPRESS_MS;
            clearToastAggState();
            followBootstrapSentForCurrentOwnership = false;

            seedLastLevelsFromClient();
            refreshTotalsCacheFromClient();
            refreshCurrentProfileInfoCache();
            refreshTargetDisplay();

            String loggedIn = getPushPlayer();
            String category = resolveTargetHiscoreCategoryFromMode();
            Long savedBankTotal = loadLastKnownBankTotalForProfile(loggedIn, category);
            if (savedBankTotal != null)
            {
                lastSentBankTotal = savedBankTotal;
            }
            else
            {
                lastSentBankTotal = -1;
            }

            pollDeviceStatusOnce();
            startDeviceStatusTicker();

            // Do NOT manually config-push.
            // On login/world-hop the world type can still be stale for a moment,
            // auto-category can incorrectly resolve as normal.
            // Re-announce shortly after the client settles.
            scheduleDelayedAutoSourceAnnounce(600);
            scheduleDelayedAutoSourceAnnounce(1500);

            executor.schedule(() -> clientThread.invokeLater(() ->
            {
                startXpTicker();
                startHeartbeatTicker();
                startSnapshotTicker();

                if (config.sendLoginSnapshot())
                {
                    enqueueSnapshotNow("login");
                    enqueueLastKnownBankNow();
                }
            }), 300, TimeUnit.MILLISECONDS);
        }
        else
        {
            followBootstrapSentForCurrentOwnership = false;
            lastDeviceSettingsSnapshot = null;
            stopXpTicker();
            stopHeartbeatTicker();
            stopSnapshotTicker();
            stopDeviceStatusTicker();
            refreshCurrentProfileInfoCache();
            refreshTargetDisplay();
            setCachedDeviceTarget(null, null);
            cachedDeviceDisplayMode = null;
            cachedDevicePinnedPlayer = null;
            cachedDevicePinnedGameMode = null;
            cachedDevicePinnedCategory = null;
            cachedDeviceApiOnly = null;
            cachedDeviceVisiblePlayer = null;
            cachedDeviceVisibleGameMode = null;
            cachedDeviceVisibleCategory = null;
            cachedDevicePluginHeartbeatFresh = null;
            cachedDevicePluginDisplayAllowed = null;
            cachedDeviceLiveOwnerPlayer = null;
            cachedDeviceLiveOwnerGameMode = null;
            cachedDeviceLiveOwnerCategory = null;

            bankOpen = false;
            if (bankDebounceFuture != null)
            {
                bankDebounceFuture.cancel(false);
                bankDebounceFuture = null;
            }

            // Also clear toast aggregation when leaving logged-in state
            clearToastAggState();
        }
    }


    private void startXpTicker()
    {
        stopXpTicker();
        xpTickCounter = 0;

        xpTickFuture = executor.scheduleAtFixedRate(() ->
            clientThread.invokeLater(() ->
            {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                    return;
                }

                if (!canSendLiveForCurrentLogin())
                {
                    return;
                }

                long totalXp = computeTotalXp();
                lastKnownTotalXp = totalXp;

                if (totalXp == lastSentTotalXp)
                {
                    xpTickCounter++;
                    return;
                }

                xpTickCounter++;

                Integer maybeTotalLevel = null;
                if (xpTickCounter % INCLUDE_TOTAL_LEVEL_EVERY_N_XP_TICKS == 0)
                {
                    int tl = client.getTotalLevel();
                    lastKnownTotalLevel = tl;
                    maybeTotalLevel = tl;
                }

                lastSentTotalXp = totalXp;

                String player = getPushPlayer();
                HttpUrl url = buildUrl("push");
                if (player == null || url == null)
                {
                    return;
                }

                PushPayload p = PushPayload.xpTick(player, nextSeq(), totalXp, maybeTotalLevel);
                enqueue(PendingKind.XP, url, p);
            })
        , XP_TICK_MS, XP_TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopXpTicker()
    {
        if (xpTickFuture != null)
        {
            xpTickFuture.cancel(false);
            xpTickFuture = null;
        }
    }

    private void startHeartbeatTicker()
    {
        stopHeartbeatTicker();

        heartbeatFuture = executor.scheduleAtFixedRate(() ->
            clientThread.invokeLater(() ->
            {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                    return;
                }

                if (!canSendLiveForCurrentLogin())
                {
                    return;
                }

                String player = getPushPlayer();
                HttpUrl url = buildUrl("push");
                if (player == null || url == null)
                {
                    return;
                }

                PushPayload p = PushPayload.heartbeat(player, nextSeq());
                enqueue(PendingKind.HEARTBEAT, url, p);
            })
        , HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeatTicker()
    {
        if (heartbeatFuture != null)
        {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void startSnapshotTicker()
    {
        stopSnapshotTicker();

        snapshotFuture = executor.scheduleAtFixedRate(() ->
            clientThread.invokeLater(() ->
            {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                    return;
                }
                enqueueSnapshotNow("periodic");
            })
        , SNAPSHOT_EVERY_MS, SNAPSHOT_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSnapshotTicker()
    {
        if (snapshotFuture != null)
        {
            snapshotFuture.cancel(false);
            snapshotFuture = null;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Skill skill = e.getSkill();
        Integer id = SKILL_TO_ID.get(skill);
        if (id == null)
        {
            return;
        }

        int newLevel = e.getLevel();
        Integer prev = lastLevels.get(skill);
        lastLevels.put(skill, newLevel);

        if (prev == null)
        {
            return; // cannot compute a delta for toasts safely
        }

        if (newLevel <= prev)
        {
            return;
        }

        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        int totalLevel = client.getTotalLevel();
        long totalXp = computeTotalXp();

        // Capture previous total level for milestone crossing detection
        int prevTotalLevel = (lastKnownTotalLevel >= 0) ? lastKnownTotalLevel : totalLevel;

        lastKnownTotalLevel = totalLevel;
        lastKnownTotalXp = totalXp;

        // NEW: toast aggregation
        if (System.currentTimeMillis() >= toastSuppressUntilMs)
        {
            recordToastLevelUp(skill, prev, newLevel, prevTotalLevel, totalLevel);
            scheduleToastAggFlush();
        }

        // Existing behavior: send the level delta as before
        PushPayload p = PushPayload.levelUpDelta(player, nextSeq(), id, newLevel, totalLevel, totalXp);
        enqueue(PendingKind.LEVEL, url, p);

    }

    // ---- Bank open/close + changes ----
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e)
    {
        if (e.getGroupId() == InterfaceID.BANK)
        {
            bankOpen = true;
            log.info("BANK widget loaded -> bankOpen=true enableBankTotalPush={}", config.enableBankTotalPush());

            if (config.enableBankTotalPush())
            {
                scheduleBankDebounced(false);
            }
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed e)
    {
        if (e.getGroupId() == InterfaceID.BANK)
        {
            log.info("BANK widget closed -> flushing final value, bankOpen was {}", bankOpen);

            if (config.enableBankTotalPush())
            {
                flushBankNow(true);
            }
            bankOpen = false;

            if (bankDebounceFuture != null)
            {
                bankDebounceFuture.cancel(false);
                bankDebounceFuture = null;
            }
        }
    }

    @Subscribe
    public void onWorldChanged(WorldChanged event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        log.info("WorldChanged -> worldTypes={} player={}", client.getWorldType(), getPlayerName());

        refreshCurrentProfileInfoCache();
        refreshTargetDisplay();

        if (config.displayMode() == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY
            || config.displayMode() == OsrsTrackerBridgeConfig.DisplayMode.FOLLOW_ACTIVE_SOURCE)
        {
            enqueueSourceAnnounceNow();
        }
    }

    // ---- Container changes: bank + equipment ----
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (e.getContainerId() == InventoryID.BANK.getId())
        {
            log.info("BANK container changed -> bankOpen={} enableBankTotalPush={} containerId={}",
                bankOpen,
                config.enableBankTotalPush(),
                e.getContainerId());
        }

        // Bank changes (existing behavior)
        if (bankOpen && config.enableBankTotalPush() && e.getContainerId() == InventoryID.BANK.getId())
        {
            scheduleBankDebounced(false);
        }
    }

    private void scheduleBankDebounced(boolean forceEvenIfSame)
    {
        log.info("BANK debounce scheduled forceEvenIfSame={} bankOpen={}", forceEvenIfSame, bankOpen);

        if (bankDebounceFuture != null)
        {
            bankDebounceFuture.cancel(false);
        }

        bankDebounceFuture = executor.schedule(() ->
            clientThread.invokeLater(() ->
            {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                    return;
                }
                if (!bankOpen)
                {
                    return;
                }
                flushBankNow(forceEvenIfSame);
            })
        , BANK_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flushBankNow(boolean forceEvenIfSame)
    {
        log.info("BANK flush start forceEvenIfSame={} bankOpen={} canSendLive={} lastSentBankTotal={}",
            forceEvenIfSame,
            bankOpen,
            canSendLiveForCurrentLogin(),
            lastSentBankTotal);

        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        long total = computeBankTotal();
        log.info("BANK flush computed total={} previous={} forceEvenIfSame={}",
            total,
            lastSentBankTotal,
            forceEvenIfSame);

        if (!forceEvenIfSame && total == lastSentBankTotal)
        {
            log.info("BANK flush skipped because total unchanged");
            return;
        }

        log.info("BANK flush sending total={} player='{}' category='{}'",
            total,
            player,
            resolveTargetHiscoreCategoryFromMode());

        lastSentBankTotal = total;
        saveLastKnownBankTotalForProfile(player, resolveTargetHiscoreCategoryFromMode(), total);

        String hiscoreCategory = deriveAutoHiscoreCategory();
        PushPayload p = PushPayload.bankUpdate(player, nextSeq(), total, hiscoreCategory);
        enqueue(PendingKind.BANK, url, p);
    }


    private void enqueueLastKnownBankNow()
    {
        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        Long savedBankTotal = loadLastKnownBankTotalForProfile(player, resolveTargetHiscoreCategoryFromMode());
        if (savedBankTotal == null)
        {
            return;
        }

        lastSentBankTotal = savedBankTotal;

        String hiscoreCategory = deriveAutoHiscoreCategory();
        PushPayload p = PushPayload.bankUpdate(player, nextSeq(), savedBankTotal, hiscoreCategory);
        enqueue(PendingKind.BANK, url, p);
    }

    private void enqueueImmediateHeartbeatNow()
    {
        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        PushPayload p = PushPayload.heartbeat(player, nextSeq());
        enqueue(PendingKind.HEARTBEAT, url, p);
    }

    private void enqueueSourceAnnounceNow()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (Boolean.TRUE.equals(cachedDeviceApiOnly))
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        String hiscoreCategory = resolveTargetHiscoreCategoryFromMode();
        String gameMode = deriveDeviceGameMode();

        PushPayload p = PushPayload.sourceAnnounce(player, nextSeq(), hiscoreCategory, gameMode);
        enqueue(PendingKind.CONFIG, url, p);

        log.info("SOURCE ANNOUNCE queued: player='{}' cat='{}' gameMode='{}' displayMode='{}'",
            player, hiscoreCategory, gameMode, config.displayMode());
    }

    // ---- Snapshot helpers ----
    private void enqueueSnapshotNow(String reason)
    {
        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        int totalLevel = client.getTotalLevel();
        long totalXp = computeTotalXp();

        lastKnownTotalLevel = totalLevel;
        lastKnownTotalXp = totalXp;

        int[] levels = new int[25];
        levels[0] = totalLevel;

        for (Map.Entry<Skill, Integer> kv : SKILL_TO_ID.entrySet())
        {
            Skill s = kv.getKey();
            int id = kv.getValue();
            levels[id] = client.getRealSkillLevel(s);
        }

        PushPayload p = PushPayload.snapshot(player, nextSeq(), levels, totalLevel, totalXp);
        enqueue(PendingKind.SNAPSHOT, url, p);
    }

    // --------------------
    // Totals calculation
    // --------------------
    private void refreshTotalsCacheFromClient()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        lastKnownTotalLevel = client.getTotalLevel();
        lastKnownTotalXp = computeTotalXp();
    }

    private long computeTotalXp()
    {
        long sum = 0;
        for (Skill s : SKILL_TO_ID.keySet())
        {
            sum += (long) client.getSkillExperience(s);
        }
        return sum;
    }

    private String deriveDeviceGameMode()
    {
        Set<WorldType> worldTypes = client.getWorldType();
        int accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);

        if (worldTypes != null)
        {
            if (worldTypes.contains(WorldType.SEASONAL))
            {
                return "league";
            }
            if (worldTypes.contains(WorldType.DEADMAN))
            {
                return "deadman";
            }
            if (worldTypes.contains(WorldType.TOURNAMENT_WORLD))
            {
                return "tournament";
            }
            if (worldTypes.contains(WorldType.FRESH_START_WORLD))
            {
                return "fresh_start";
            }
        }

        switch (accountType)
        {
            case 1:
                return "ironman";

            case 2:
                return "u_ironman";

            case 3:
                return "hc_ironman";

            case 4:
                return "group_ironman";

            case 5:
                return "group_hc_ironman";

            case 6:
                return "group_unranked_ironman";

            default:
                return "normal";
        }
    }

    private String deriveDeviceGameModeFromSnapshot(Set<WorldType> worldTypes, int accountType)
    {
        if (worldTypes != null)
        {
            if (worldTypes.contains(WorldType.SEASONAL))
            {
                return "league";
            }
            if (worldTypes.contains(WorldType.DEADMAN))
            {
                return "deadman";
            }
            if (worldTypes.contains(WorldType.TOURNAMENT_WORLD))
            {
                return "tournament";
            }
            if (worldTypes.contains(WorldType.FRESH_START_WORLD))
            {
                return "fresh_start";
            }
        }

        switch (accountType)
        {
            case 1:
                return "ironman";

            case 2:
                return "u_ironman";

            case 3:
                return "hc_ironman";

            case 4:
                return "group_ironman";

            case 5:
                return "group_hc_ironman";

            case 6:
                return "group_unranked_ironman";

            default:
                return "normal";
        }
    }

    // --------------------
    // Name / URL helpers
    // --------------------
private String deriveAutoHiscoreCategory()
{
    Set<WorldType> worldTypes = client.getWorldType();
    int accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);

    String resolved;

    if (worldTypes != null)
    {
        if (worldTypes.contains(WorldType.SEASONAL))
        {
            resolved = "hiscore_oldschool_seasonal";
            log.info("deriveAutoHiscoreCategory -> {} (worldTypes={}, accountType={})", resolved, worldTypes, accountType);
            return resolved;
        }
        if (worldTypes.contains(WorldType.DEADMAN))
        {
            resolved = "hiscore_oldschool_deadman";
            log.info("deriveAutoHiscoreCategory -> {} (worldTypes={}, accountType={})", resolved, worldTypes, accountType);
            return resolved;
        }
        if (worldTypes.contains(WorldType.TOURNAMENT_WORLD))
        {
            resolved = "hiscore_oldschool_tournament";
            log.info("deriveAutoHiscoreCategory -> {} (worldTypes={}, accountType={})", resolved, worldTypes, accountType);
            return resolved;
        }
        if (worldTypes.contains(WorldType.FRESH_START_WORLD))
        {
            resolved = "hiscore_oldschool_fresh_start";
            log.info("deriveAutoHiscoreCategory -> {} (worldTypes={}, accountType={})", resolved, worldTypes, accountType);
            return resolved;
        }
    }

    switch (accountType)
    {
        case 1:
            resolved = "hiscore_oldschool_ironman";
            break;

        case 2:
            resolved = "hiscore_oldschool_ultimate";
            break;

        case 3:
            resolved = "hiscore_oldschool_hardcore_ironman";
            break;

        case 4: // Group Ironman
        case 5: // Group Hardcore Ironman
        case 6: // Unranked Group Ironman
            resolved = "hiscore_oldschool";
            break;

        default:
            resolved = "hiscore_oldschool";
            break;
    }

    log.info("deriveAutoHiscoreCategory -> {} (worldTypes={}, accountType={})", resolved, worldTypes, accountType);
    return resolved;
}

    private String displayNameForHiscoreCategory(String endpoint)
    {
        if (endpoint == null || endpoint.trim().isEmpty())
        {
            return "-";
        }

        switch (endpoint.trim())
        {
            case "hiscore_oldschool":
                return "Normal";

            case "hiscore_oldschool_ironman":
                return "Ironman";

            case "hiscore_oldschool_hardcore_ironman":
                return "Hardcore Ironman";

            case "hiscore_oldschool_ultimate":
                return "Ultimate Ironman";

            case "hiscore_oldschool_seasonal":
                return "Seasonal";

            case "hiscore_oldschool_deadman":
                return "Deadman";

            case "hiscore_oldschool_tournament":
                return "Tournament";

            case "hiscore_oldschool_fresh_start":
                return "Fresh Start";

            default:
                return endpoint.trim();
        }
    }

private String deriveCurrentModeDisplay()
{
    Set<WorldType> worldTypes = client.getWorldType();
    int accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);

    if (worldTypes != null)
    {
        if (worldTypes.contains(WorldType.SEASONAL))
        {
            return "Seasonal";
        }
        if (worldTypes.contains(WorldType.DEADMAN))
        {
            return "Deadman";
        }
        if (worldTypes.contains(WorldType.TOURNAMENT_WORLD))
        {
            return "Tournament";
        }
        if (worldTypes.contains(WorldType.FRESH_START_WORLD))
        {
            return "Fresh Start";
        }
    }

    switch (accountType)
    {
        case 1:
            return "Ironman";

        case 2:
            return "Ultimate Ironman";

        case 3:
            return "Hardcore Ironman";

        case 4:
            return "Group Ironman";

        case 5:
            return "Group Hardcore Ironman";

        case 6:
            return "Unranked Group Ironman";

        default:
            return "Normal";
    }
}

    private CurrentProfileInfo getCurrentProfileInfo()
    {
        String playerName = getPlayerName();
        String hiscoreCategory = deriveAutoHiscoreCategory();
        String hiscoreCategoryDisplay = displayNameForHiscoreCategory(hiscoreCategory);
        String modeDisplay = deriveCurrentModeDisplay();

        return new CurrentProfileInfo(
            playerName == null || playerName.trim().isEmpty() ? "-" : playerName.trim(),
            hiscoreCategoryDisplay,
            modeDisplay
        );
    }
    private void refreshCurrentProfileInfoCache()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            cachedCurrentPlayerName = "-";
            cachedCurrentHiscoreCategoryDisplay = "-";
            cachedCurrentModeDisplay = "-";
            return;
        }

        CurrentProfileInfo info = getCurrentProfileInfo();
        cachedCurrentPlayerName = info.playerName;
        cachedCurrentHiscoreCategoryDisplay = info.hiscoreCategoryDisplay;
        cachedCurrentModeDisplay = info.modeDisplay;
    }

    private String trimmedOrNull(String value)
    {
        if (value == null)
        {
            return null;
        }

        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private String resolveTargetPlayerFromMode()
    {
        if (config.apiOnly())
        {
            return trimmedOrNull(config.pinnedPlayer());
        }

        OsrsTrackerBridgeConfig.DisplayMode mode = config.displayMode();
        if (mode == null)
        {
            mode = OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
        }

        switch (mode)
        {
            case FIXED_PROFILE:
            case FIXED_PLAYER_AUTO_CATEGORY:
                return trimmedOrNull(config.pinnedPlayer());

            case FOLLOW_ACTIVE_SOURCE:
                return getPlayerName();

            default:
                return trimmedOrNull(config.pinnedPlayer());
        }
    }

    private String resolveTargetHiscoreCategoryFromMode()
    {
        if (config.apiOnly())
        {
            OsrsTrackerBridgeConfig.GameModeChoice choice = config.pinnedGameMode();
            if (choice == null)
            {
                return "hiscore_oldschool";
            }
            return choice.endpoint();
        }

        OsrsTrackerBridgeConfig.DisplayMode mode = config.displayMode();
        if (mode == null)
        {
            mode = OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
        }

        switch (mode)
        {
            case FIXED_PROFILE:
            {
                OsrsTrackerBridgeConfig.GameModeChoice choice = config.pinnedGameMode();
                if (choice == null)
                {
                    return "hiscore_oldschool";
                }
                return choice.endpoint();
            }

            case FIXED_PLAYER_AUTO_CATEGORY:
            case FOLLOW_ACTIVE_SOURCE:
                return deriveAutoHiscoreCategory();

            default:
                return deriveAutoHiscoreCategory();
        }
    }

    private String resolveTargetGameModeFromMode()
    {
        if (config.apiOnly())
        {
            OsrsTrackerBridgeConfig.GameModeChoice choice = config.pinnedGameMode();
            return choice == null ? "normal" : choice.gameModeKey();
        }

        OsrsTrackerBridgeConfig.DisplayMode mode = config.displayMode();
        if (mode == null)
        {
            mode = OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY;
        }

        switch (mode)
        {
            case FIXED_PROFILE:
            {
                OsrsTrackerBridgeConfig.GameModeChoice choice = config.pinnedGameMode();
                return choice == null ? "normal" : choice.gameModeKey();
            }

            case FIXED_PLAYER_AUTO_CATEGORY:
            case FOLLOW_ACTIVE_SOURCE:
                return deriveDeviceGameMode();

            default:
                return deriveDeviceGameMode();
        }
    }

        private OsrsTrackerBridgeConfig.GameModeChoice mapGameModeKeyToChoice(String gameModeKey)
    {
        if (gameModeKey == null || gameModeKey.trim().isEmpty())
        {
            return OsrsTrackerBridgeConfig.GameModeChoice.NORMAL;
        }

        for (OsrsTrackerBridgeConfig.GameModeChoice choice
            : OsrsTrackerBridgeConfig.GameModeChoice.values())
        {
            if (gameModeKey.equals(choice.gameModeKey()))
            {
                return choice;
            }
        }

        return OsrsTrackerBridgeConfig.GameModeChoice.NORMAL;
    }

    private String endpointToDeviceGameMode(String endpoint)
    {
        if (endpoint == null || endpoint.trim().isEmpty())
        {
            return "normal";
        }

        switch (endpoint.trim())
        {
            case "hiscore_oldschool":
                return "normal";
            case "hiscore_oldschool_ironman":
                return "ironman";
            case "hiscore_oldschool_hardcore_ironman":
                return "hc_ironman";
            case "hiscore_oldschool_ultimate":
                return "u_ironman";
            case "hiscore_oldschool_seasonal":
                return "league";
            case "hiscore_oldschool_deadman":
                return "deadman";
            case "hiscore_oldschool_tournament":
                return "tournament";
            case "hiscore_oldschool_fresh_start":
                return "fresh_start";
            default:
                return "normal";
        }
    }

    private void refreshTargetDisplay()
    {
        String player = resolveTargetPlayerFromMode();
        String category = resolveTargetHiscoreCategoryFromMode();

        log.info(
            "refreshTargetDisplay -> mode={} apiOnly={} loggedIn={} resolvedPlayer={} resolvedCategory={} worldTypes={}",
            config.displayMode(),
            config.apiOnly(),
            getPlayerName(),
            player,
            category,
            client.getWorldType()
        );

        updateTargetDisplay(player, category);
    }

    private static final class DeviceStatusResponse
    {
        String displayMode;
        String pinnedPlayer;
        String pinnedGameMode;
        String pinnedHiscoreCategory;
        Boolean apiOnly;

        String targetPlayer;
        String targetGameMode;
        String targetHiscoreCategory;

        String visiblePlayer;
        String visibleGameMode;
        String visibleHiscoreCategory;

        Boolean pluginHeartbeatFresh;
        Boolean pluginDisplayAllowed;

        String liveOwnerPlayer;
        String liveOwnerGameMode;
        String liveOwnerHiscoreCategory;
    }

    private static final class CurrentProfileInfo
    {
        final String playerName;
        final String hiscoreCategoryDisplay;
        final String modeDisplay;

        CurrentProfileInfo(String playerName, String hiscoreCategoryDisplay, String modeDisplay)
        {
            this.playerName = playerName;
            this.hiscoreCategoryDisplay = hiscoreCategoryDisplay;
            this.modeDisplay = modeDisplay;
        }
    }

    private String normalizePlayerForMatch(String s)
    {
        if (s == null)
        {
            return "";
        }

        String n = s.trim().toLowerCase().replace('_', ' ');
        while (n.contains("  "))
        {
            n = n.replace("  ", " ");
        }
        return n;
    }

    private void setCachedDeviceTarget(String player, String category)
    {
        cachedDeviceTargetPlayer = (player == null || player.trim().isEmpty()) ? null : player.trim();
        cachedDeviceTargetCategory = (category == null || category.trim().isEmpty()) ? null : category.trim();
    }
    private void setOptimisticDeviceConfigCache(
    String displayMode,
    String pinnedPlayer,
    String pinnedGameMode,
    String pinnedCategory,
    boolean apiOnly,
    String targetPlayer,
    String targetGameMode,
    String targetCategory)
    {
        cachedDeviceDisplayMode =
            (displayMode == null || displayMode.trim().isEmpty()) ? null : displayMode.trim();

        cachedDevicePinnedPlayer =
            (pinnedPlayer == null || pinnedPlayer.trim().isEmpty()) ? null : pinnedPlayer.trim();

        cachedDevicePinnedGameMode =
            (pinnedGameMode == null || pinnedGameMode.trim().isEmpty()) ? null : pinnedGameMode.trim();

        cachedDevicePinnedCategory =
            (pinnedCategory == null || pinnedCategory.trim().isEmpty()) ? null : pinnedCategory.trim();

        cachedDeviceApiOnly = apiOnly;

        cachedDeviceTargetPlayer =
            (targetPlayer == null || targetPlayer.trim().isEmpty()) ? null : targetPlayer.trim();

        cachedDeviceTargetGameMode =
            (targetGameMode == null || targetGameMode.trim().isEmpty()) ? null : targetGameMode.trim();

        cachedDeviceTargetCategory =
            (targetCategory == null || targetCategory.trim().isEmpty()) ? null : targetCategory.trim();

        lastDeviceSettingsSnapshot = buildDeviceSettingsSnapshot();
        refreshDeviceStatusPanelFromAnyThread(true);
    }

    private String buildDeviceSettingsSnapshot()
    {
        return String.valueOf(cachedDeviceDisplayMode) + "\n"
            + String.valueOf(cachedDevicePinnedPlayer) + "\n"
            + String.valueOf(cachedDevicePinnedGameMode) + "\n"
            + String.valueOf(cachedDevicePinnedCategory) + "\n"
            + String.valueOf(cachedDeviceApiOnly) + "\n"
            + String.valueOf(cachedDeviceTargetPlayer) + "\n"
            + String.valueOf(cachedDeviceTargetGameMode) + "\n"
            + String.valueOf(cachedDeviceTargetCategory);
    }


    private void refreshDeviceStatusPanelFromAnyThread(boolean applyControls)
    {
        if (sidePanel == null)
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            refreshCurrentProfileInfoCache();

            javax.swing.SwingUtilities.invokeLater(() ->
            {
                if (sidePanel != null)
                {
                    sidePanel.refreshDeviceStatusBlock();
                }
            });
        });
    }


    private void setCachedDeviceStatus(DeviceStatusResponse status)
    {
        if (status == null)
        {
            return;
        }

        cachedDeviceDisplayMode =
            (status.displayMode == null || status.displayMode.trim().isEmpty())
                ? null
                : status.displayMode.trim();

        cachedDevicePinnedPlayer =
            (status.pinnedPlayer == null || status.pinnedPlayer.trim().isEmpty())
                ? null
                : status.pinnedPlayer.trim();

        cachedDevicePinnedGameMode =
            (status.pinnedGameMode == null || status.pinnedGameMode.trim().isEmpty())
                ? null
                : status.pinnedGameMode.trim();

        cachedDevicePinnedCategory =
            (status.pinnedHiscoreCategory == null || status.pinnedHiscoreCategory.trim().isEmpty())
                ? null
                : status.pinnedHiscoreCategory.trim();

        cachedDeviceApiOnly = status.apiOnly;

        cachedDeviceTargetPlayer =
            (status.targetPlayer == null || status.targetPlayer.trim().isEmpty())
                ? null
                : status.targetPlayer.trim();

        cachedDeviceTargetGameMode =
            (status.targetGameMode == null || status.targetGameMode.trim().isEmpty())
                ? null
                : status.targetGameMode.trim();

        cachedDeviceTargetCategory =
            (status.targetHiscoreCategory == null || status.targetHiscoreCategory.trim().isEmpty())
                ? null
                : status.targetHiscoreCategory.trim();

        cachedDeviceVisiblePlayer =
            (status.visiblePlayer == null || status.visiblePlayer.trim().isEmpty())
                ? null
                : status.visiblePlayer.trim();

        cachedDeviceVisibleGameMode =
            (status.visibleGameMode == null || status.visibleGameMode.trim().isEmpty())
                ? null
                : status.visibleGameMode.trim();

        cachedDeviceVisibleCategory =
            (status.visibleHiscoreCategory == null || status.visibleHiscoreCategory.trim().isEmpty())
                ? null
                : status.visibleHiscoreCategory.trim();

        cachedDevicePluginHeartbeatFresh = status.pluginHeartbeatFresh;
        cachedDevicePluginDisplayAllowed = status.pluginDisplayAllowed;

        cachedDeviceLiveOwnerPlayer =
            (status.liveOwnerPlayer == null || status.liveOwnerPlayer.trim().isEmpty())
                ? null
                : status.liveOwnerPlayer.trim();

        cachedDeviceLiveOwnerGameMode =
            (status.liveOwnerGameMode == null || status.liveOwnerGameMode.trim().isEmpty())
                ? null
                : status.liveOwnerGameMode.trim();

        cachedDeviceLiveOwnerCategory =
            (status.liveOwnerHiscoreCategory == null || status.liveOwnerHiscoreCategory.trim().isEmpty())
                ? null
                : status.liveOwnerHiscoreCategory.trim();

        log.info("DEVICE STATUS cached: mode='{}' apiOnly={} targetPlayer='{}' targetCategory='{}' visiblePlayer='{}' visibleCategory='{}' heartbeatFresh={} pluginDisplayAllowed={} pinnedPlayer='{}' pinnedCategory='{}'",
    cachedDeviceDisplayMode,
    cachedDeviceApiOnly,
    cachedDeviceTargetPlayer,
    cachedDeviceTargetCategory,
    cachedDeviceVisiblePlayer,
    cachedDeviceVisibleCategory,
    cachedDevicePluginHeartbeatFresh,
    cachedDevicePluginDisplayAllowed,
    cachedDevicePinnedPlayer,
    cachedDevicePinnedCategory);


    maybeBootstrapFollowActiveOwnership();

    String newSnapshot = buildDeviceSettingsSnapshot();
    boolean applyControls = !java.util.Objects.equals(lastDeviceSettingsSnapshot, newSnapshot);

    if (applyControls)
    {
        lastDeviceSettingsSnapshot = newSnapshot;
    }

    refreshDeviceStatusPanelFromAnyThread(applyControls);
    }

        private void maybeBootstrapFollowActiveOwnership()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            followBootstrapSentForCurrentOwnership = false;
            return;
        }

        if (Boolean.TRUE.equals(cachedDeviceApiOnly))
        {
            followBootstrapSentForCurrentOwnership = false;
            return;
        }

        if (!"FOLLOW_ACTIVE_SOURCE".equals(cachedDeviceDisplayMode))
        {
            followBootstrapSentForCurrentOwnership = false;
            return;
        }

        String loggedIn = getPushPlayer();
        if (loggedIn == null || loggedIn.trim().isEmpty())
        {
            followBootstrapSentForCurrentOwnership = false;
            return;
        }

        String owner = cachedDeviceLiveOwnerPlayer;
        boolean iOwnDeviceNow =
            owner != null
                && normalizePlayerForMatch(loggedIn).equals(normalizePlayerForMatch(owner))
                && Boolean.TRUE.equals(cachedDevicePluginHeartbeatFresh)
                && Boolean.TRUE.equals(cachedDevicePluginDisplayAllowed);

        if (!iOwnDeviceNow)
        {
            followBootstrapSentForCurrentOwnership = false;
            return;
        }

        if (followBootstrapSentForCurrentOwnership)
        {
            return;
        }

        followBootstrapSentForCurrentOwnership = true;

        log.info("FOLLOW bootstrap starting for new ownership. loggedIn='{}' owner='{}'", loggedIn, owner);
        clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                return;
            }

            if (!canSendLiveForCurrentLogin())
            {
                return;
            }

            log.info("FOLLOW bootstrap heartbeat queued for '{}'", loggedIn);
            enqueueImmediateHeartbeatNow();

            log.info("FOLLOW bootstrap snapshot queued for '{}'", loggedIn);
            enqueueSnapshotNow("follow-takeover");

            log.info("FOLLOW bootstrap bank queued for '{}'", loggedIn);
            enqueueLastKnownBankNow();
        });
    }

    private boolean canSendLiveForCurrentLogin()
    {
        String loggedIn = getPushPlayer();
        if (loggedIn == null)
        {
            log.info("LIVE GATE -> false: loggedIn is null");
            return false;
        }

        if (Boolean.TRUE.equals(cachedDeviceApiOnly))
        {
            log.info("LIVE GATE -> false: apiOnly=true loggedIn='{}'", loggedIn);
            return false;
        }

        String mode = cachedDeviceDisplayMode;
        String normalizedLoggedIn = normalizePlayerForMatch(loggedIn);

        if ("FIXED_PROFILE".equals(mode))
        {
            String pinned = cachedDevicePinnedPlayer;
            if (pinned == null)
            {
                log.info("LIVE GATE -> false: mode='{}' pinnedPlayer is null loggedIn='{}'",
                    mode, loggedIn);
                return false;
            }

            boolean allowed = normalizedLoggedIn.equals(normalizePlayerForMatch(pinned));
            log.info("LIVE GATE -> {}: mode='{}' loggedIn='{}' pinned='{}'",
                allowed, mode, loggedIn, pinned);
            return allowed;
        }

        if ("FIXED_PLAYER_AUTO_CATEGORY".equals(mode))
        {
            String target = cachedDeviceTargetPlayer;
            if (target == null)
            {
                log.info("LIVE GATE -> false: mode='{}' targetPlayer is null loggedIn='{}'",
                    mode, loggedIn);
                return false;
            }

            boolean allowed = normalizedLoggedIn.equals(normalizePlayerForMatch(target));
            log.info("LIVE GATE -> {}: mode='{}' loggedIn='{}' target='{}'",
                allowed, mode, loggedIn, target);
            return allowed;
        }

        if ("FOLLOW_ACTIVE_SOURCE".equals(mode))
        {
            if (Boolean.TRUE.equals(cachedDevicePluginHeartbeatFresh))
            {
                String target = cachedDeviceTargetPlayer;
                if (target == null)
                {
                    log.info("LIVE GATE -> false: mode='{}' heartbeatFresh=true but target is null loggedIn='{}'",
                        mode, loggedIn);
                    return false;
                }

                boolean allowed = normalizedLoggedIn.equals(normalizePlayerForMatch(target));
                log.info("LIVE GATE -> {}: mode='{}' heartbeatFresh=true pluginDisplayAllowed={} loggedIn='{}' target='{}' visiblePlayer='{}' visibleCategory='{}'",
                    allowed,
                    mode,
                    cachedDevicePluginDisplayAllowed,
                    loggedIn,
                    target,
                    cachedDeviceVisiblePlayer,
                    cachedDeviceVisibleCategory);
                return allowed;
            }

            log.info("LIVE GATE -> true: mode='{}' heartbeatFresh=false loggedIn='{}' target='{}'",
                mode, loggedIn, cachedDeviceTargetPlayer);
            return true;
        }

        String target = cachedDeviceTargetPlayer;
        if (target == null)
        {
            log.info("LIVE GATE -> false: fallback mode='{}' target is null loggedIn='{}'",
                mode, loggedIn);
            return false;
        }

        boolean allowed = normalizedLoggedIn.equals(normalizePlayerForMatch(target));
        log.info("LIVE GATE -> {}: fallback mode='{}' loggedIn='{}' target='{}'",
            allowed, mode, loggedIn, target);
        return allowed;
    }

    private void startDeviceStatusTicker()
    {
        stopDeviceStatusTicker();
        pollDeviceStatusOnce();

        deviceStatusFuture = executor.scheduleAtFixedRate(
            this::pollDeviceStatusOnce,
            DEVICE_STATUS_POLL_MS,
            DEVICE_STATUS_POLL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopDeviceStatusTicker()
    {
        if (deviceStatusFuture != null)
        {
            deviceStatusFuture.cancel(false);
            deviceStatusFuture = null;
        }
    }

    private void pollDeviceStatusOnce()
    {
        HttpUrl url = buildUrl("status");
        if (url == null)
        {
            return;
        }

        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException ex)
            {
                log.info("ESP32 status poll failed: {}", ex.toString());
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException
            {
                try (Response r = resp)
                {
                    if (!r.isSuccessful() || r.body() == null)
                    {
                        log.info("ESP32 status poll HTTP {}", r.code());
                        return;
                    }

                    String body = r.body().string();
                    DeviceStatusResponse status = gson.fromJson(body, DeviceStatusResponse.class);
                    if (status == null)
                    {
                        return;
                    }

                    setCachedDeviceStatus(status);
                }
            }
        });
    }

    private void scheduleTargetRefreshAndConfigSync(boolean sendConfig)
    {
        clientThread.invokeLater(() ->
        {
            refreshTargetDisplay();
            if (sendConfig)
            {
                pushConfigNowValidated();
            }
        });
    }

    private void scheduleDelayedAutoSourceAnnounce(long delayMs)
    {
        executor.schedule(() -> clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                return;
            }

            refreshTargetDisplay();

            OsrsTrackerBridgeConfig.DisplayMode mode = config.displayMode();
            if (mode == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY
                || mode == OsrsTrackerBridgeConfig.DisplayMode.FOLLOW_ACTIVE_SOURCE)
            {
                enqueueSourceAnnounceNow();
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private static final Pattern OSRS_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9 _-]{1,12}$");

    private boolean isValidPlayerNameLocal(String name)
    {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        return OSRS_NAME_PATTERN.matcher(n).matches();
    }

    private boolean isValidCategoryLocal(String category)
    {
        if (category == null) return false;
        String c = category.trim();
        return !c.isEmpty();
    }

    private String resolveFixedPlayerAutoCategoryForConfigPush(String targetPlayer)
    {
        if (targetPlayer == null || targetPlayer.trim().isEmpty())
        {
            return null;
        }

        String loggedIn = getPushPlayer();
        String trimmedTarget = targetPlayer.trim();

        if (loggedIn != null
            && normalizePlayerForMatch(loggedIn).equals(normalizePlayerForMatch(trimmedTarget)))
        {
            String derived = deriveAutoHiscoreCategory();
            log.info("AUTO CAT using logged-in player category: player='{}' category='{}'",
                trimmedTarget, derived);
            return derived;
        }

        String detected = detectHiscoreCategoryForPlayer(trimmedTarget);
        log.info("AUTO CAT detected by probing: player='{}' category='{}'",
            trimmedTarget, detected);

        return detected;
    }

        private String resolveFixedPlayerAutoCategoryForConfigPushUsingSnapshot(
            String targetPlayer,
            String loggedInPlayerSnapshot,
            String loggedInAutoCategorySnapshot)
        {
            if (targetPlayer == null || targetPlayer.trim().isEmpty())
            {
                return null;
            }

            String trimmedTarget = targetPlayer.trim();

            if (loggedInPlayerSnapshot != null
                && normalizePlayerForMatch(loggedInPlayerSnapshot).equals(normalizePlayerForMatch(trimmedTarget)))
            {
                log.info("AUTO CAT using logged-in player category snapshot: player='{}' category='{}'",
                    trimmedTarget, loggedInAutoCategorySnapshot);
                return loggedInAutoCategorySnapshot;
            }

            String detected = detectHiscoreCategoryForPlayer(trimmedTarget);
            log.info("AUTO CAT detected by probing: player='{}' category='{}'",
                trimmedTarget, detected);

            return detected;
        }

    private String detectHiscoreCategoryForPlayer(String player)
    {
        if (player == null || player.trim().isEmpty())
        {
            return null;
        }

        String trimmedPlayer = player.trim();

        for (String category : FIXED_PLAYER_AUTO_CATEGORY_PROBE_ORDER)
        {
            HttpUrl url = buildHiscoresLiteUrl(category, trimmedPlayer);
            if (url == null)
            {
                continue;
            }

            Request req = new Request.Builder().url(url).get().build();

            try (Response resp = http.newCall(req).execute())
            {
                int code = resp.code();
                log.info("AUTO CAT probe: player='{}' category='{}' http={}", trimmedPlayer, category, code);

                if (code >= 200 && code < 300)
                {
                    return category;
                }
            }
            catch (IOException ex)
            {
                log.info("AUTO CAT probe failed: player='{}' category='{}' err={}",
                    trimmedPlayer, category, ex.toString());
            }
        }

        return null;
    }

    private HttpUrl buildHiscoresLiteUrl(String categoryEndpoint, String player)
    {
        // Target format:
        // https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player=<name>
        try
        {
            String encodedPath = "/m=" + categoryEndpoint + "/index_lite.ws";

            return new HttpUrl.Builder()
                .scheme("https")
                .host("secure.runescape.com")
                .encodedPath(encodedPath)
                .addQueryParameter("player", player)
                .build();
        }
        catch (IllegalArgumentException ex)
        {
            log.info("Invalid hiscores URL build: cat='{}' player='{}' err={}", categoryEndpoint, player, ex.toString());
            return null;
        }
    }

    private void pushConfigNowValidated()
    {
        final String player = resolveTargetPlayerFromMode();
        final HttpUrl deviceUrl = buildUrl("push");

        if (deviceUrl == null)
        {
            log.info("Config push blocked: device URL invalid.");
            uiSetPanelStatus("Push blocked: invalid device URL.");
            return;
        }

        if (!isValidPlayerNameLocal(player))
        {
            log.info("Config push blocked: invalid player name '{}'", player);
            uiSetPanelStatus("Push blocked: invalid player name.");
            return;
        }

        uiSetPanelStatus("Validating player/category...");

        final OsrsTrackerBridgeConfig.DisplayMode mode =
            config.displayMode() == null
                ? OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY
                : config.displayMode();

        final boolean apiOnly = config.apiOnly();
        final String displayMode = mode.name();
        final String pinnedPlayer = config.pinnedPlayer() == null ? "" : config.pinnedPlayer().trim();

        OsrsTrackerBridgeConfig.GameModeChoice pinnedChoice = config.pinnedGameMode();
        if (pinnedChoice == null)
        {
            pinnedChoice = OsrsTrackerBridgeConfig.GameModeChoice.NORMAL;
        }
        final String pinnedGameMode = pinnedChoice.gameModeKey();
        final String pinnedCategory = pinnedChoice.endpoint();

        final String loggedInPlayerSnapshot = getPushPlayer();
        final String loggedInAutoCategorySnapshot = deriveAutoHiscoreCategory();
        final Set<WorldType> loggedInWorldTypesSnapshot = client.getWorldType();
        final int loggedInAccountTypeSnapshot = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
        final String loggedInDeviceGameModeSnapshot =
            deriveDeviceGameModeFromSnapshot(loggedInWorldTypesSnapshot, loggedInAccountTypeSnapshot);

        executor.execute(() ->
        {
            String category;

            if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY)
            {
                category = resolveFixedPlayerAutoCategoryForConfigPushUsingSnapshot(
                    player,
                    loggedInPlayerSnapshot,
                    loggedInAutoCategorySnapshot
                );
            }
            else if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FOLLOW_ACTIVE_SOURCE)
            {
                category = loggedInAutoCategorySnapshot;
            }
            else if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PROFILE)
            {
                category = pinnedCategory;
            }
            else if (apiOnly)
            {
                category = pinnedCategory;
            }
            else
            {
                category = loggedInAutoCategorySnapshot;
            }

            updateTargetDisplay(player, category);

            final String gameMode;

            if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PLAYER_AUTO_CATEGORY)
            {
                if (loggedInPlayerSnapshot != null
                    && normalizePlayerForMatch(loggedInPlayerSnapshot).equals(normalizePlayerForMatch(player)))
                {
                    gameMode = loggedInDeviceGameModeSnapshot;
                }
                else
                {
                    gameMode = endpointToDeviceGameMode(category);
                }
            }
            else if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FOLLOW_ACTIVE_SOURCE)
            {
                gameMode = loggedInDeviceGameModeSnapshot;
            }
            else if (!apiOnly && mode == OsrsTrackerBridgeConfig.DisplayMode.FIXED_PROFILE)
            {
                gameMode = pinnedGameMode;
            }
            else if (apiOnly)
            {
                gameMode = endpointToDeviceGameMode(category);
            }
            else
            {
                gameMode = loggedInDeviceGameModeSnapshot;
            }

            if (category == null || category.trim().isEmpty())
            {
                log.info("Config push blocked: could not detect hiscore category for player '{}'", player);
                uiSetPanelStatus("Push blocked: could not detect player category.");
                return;
            }

            if (!isValidCategoryLocal(category))
            {
                log.info("Config push blocked: invalid hiscore category '{}'", category);
                uiSetPanelStatus("Push blocked: invalid hiscore category.");
                return;
            }

            final HttpUrl hiscoresUrl = buildHiscoresLiteUrl(category.trim(), player.trim());
            if (hiscoresUrl == null)
            {
                log.info("Config push blocked: cannot build hiscores validation URL.");
                uiSetPanelStatus("Push blocked: validation URL error.");
                return;
            }

            Request req = new Request.Builder().url(hiscoresUrl).get().build();

            try (Response resp = http.newCall(req).execute())
            {
                int code = resp.code();

                if (code < 200 || code >= 300)
                {
                    log.info("Config push blocked: hiscores validation failed HTTP {} (player='{}' cat='{}')",
                        code, player, category);
                    uiSetPanelStatus("Push blocked: player/category not valid (HTTP " + code + ").");
                    return;
                }
            }
            catch (IOException ex)
            {
                log.info("Config push blocked: hiscores validation IO error (player='{}' cat='{}') err={}",
                    player, category, ex.toString());
                uiSetPanelStatus("Push blocked: validation request failed.");
                return;
            }

            PushPayload p = PushPayload.configUpdate(
                player,
                nextSeq(),
                player,
                category,
                gameMode,
                displayMode,
                pinnedPlayer,
                pinnedGameMode,
                pinnedCategory,
                apiOnly
            );
            enqueue(PendingKind.CONFIG, deviceUrl, p);
            log.info("CONFIG push queued: player='{}' category='{}'", player, category);
            uiSetPanelStatus("Push queued for " + player + " / " + category);

            setOptimisticDeviceConfigCache(
                displayMode,
                pinnedPlayer,
                pinnedGameMode,
                pinnedCategory,
                apiOnly,
                player,
                gameMode,
                category
            );

            log.info("CONFIG cache updated: mode='{}' apiOnly={} player='{}' category='{}' pinnedPlayer='{}' pinnedCategory='{}'",
                displayMode,
                apiOnly,
                player,
                category,
                pinnedPlayer,
                pinnedCategory);

            boolean targetIsLoggedIn =
                loggedInPlayerSnapshot != null
                    && normalizePlayerForMatch(loggedInPlayerSnapshot).equals(normalizePlayerForMatch(player));

            if (targetIsLoggedIn && !apiOnly)
            {
                log.info("CONFIG bootstrap starting. loggedIn='{}' target='{}' mode='{}' category='{}'",
                    loggedInPlayerSnapshot,
                    player,
                    displayMode,
                    category);

                clientThread.invokeLater(() ->
                {
                    log.info("BOOTSTRAP heartbeat queued for '{}'", player);
                    enqueueImmediateHeartbeatNow();

                    log.info("BOOTSTRAP snapshot queued for '{}' reason='config-push'", player);
                    enqueueSnapshotNow("config-push");

                    log.info("BOOTSTRAP bank queued for '{}'", player);
                    enqueueLastKnownBankNow();
                });
            }
            else
            {
                log.info("CONFIG bootstrap skipped. loggedIn='{}' target='{}' targetIsLoggedIn={} apiOnly={} mode='{}'",
                    loggedInPlayerSnapshot,
                    player,
                    targetIsLoggedIn,
                    apiOnly,
                    displayMode);
            }
        });
    }

    private void seedLastLevelsFromClient()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        for (Map.Entry<Skill, Integer> kv : SKILL_TO_ID.entrySet())
        {
            Skill s = kv.getKey();
            int level = client.getRealSkillLevel(s);
            lastLevels.put(s, level);
        }
    }

    private String getPlayerName()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            return null;
        }
        String name = client.getLocalPlayer().getName().trim();
        return name.isEmpty() ? null : name;
    }

    private String getPushPlayer()
    {
        // Live plugin pushes must always be tagged with the actual logged-in RuneLite player.
        // The manual override is for device config/API identity, not for pretending live data belongs
        // to a different account.
        String p = getPlayerName();
        if (p == null) return null;
        p = p.trim();
        return p.isEmpty() ? null : p;
    }

    private HttpUrl buildUrl(String pathSegment)
    {
        String hostRaw = config.deviceHost().trim();
        if (hostRaw.isEmpty())
        {
            return null;
        }

        hostRaw = hostRaw.replace("http://", "").replace("https://", "");
        while (hostRaw.endsWith("/"))
        {
            hostRaw = hostRaw.substring(0, hostRaw.length() - 1);
        }

        String host = hostRaw;
        int port = config.devicePort();
        int idx = hostRaw.lastIndexOf(':');
        if (idx > 0 && idx < hostRaw.length() - 1)
        {
            String maybePort = hostRaw.substring(idx + 1);
            String maybeHost = hostRaw.substring(0, idx);
            try
            {
                int parsed = Integer.parseInt(maybePort);
                if (parsed > 0 && parsed < 65536)
                {
                    host = maybeHost;
                    port = parsed;
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        try
        {
            return new HttpUrl.Builder()
                .scheme("http")
                .host(host)
                .port(port)
                .addPathSegment(pathSegment)
                .build();
        }
        catch (IllegalArgumentException ex)
        {
            log.info("Invalid deviceHost '{}': {}", hostRaw, ex.toString());
            return null;
        }
    }

    private long nextSeq()
    {
        synchronized (sendLock)
        {
            long now = System.currentTimeMillis();
            if (seq < now)
            {
                seq = now;
            }
            return ++seq;
        }
    }

    private String normalizePartForBankKey(String value)
    {
        if (value == null)
        {
            return null;
        }

        String v = value.trim();
        if (v.isEmpty())
        {
            return null;
        }

        StringBuilder out = new StringBuilder(v.length() * 3);
        for (int i = 0; i < v.length(); i++)
        {
            char c = v.charAt(i);

            boolean safe =
                (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.';

            if (safe)
            {
                out.append(c);
            }
            else
            {
                out.append('%');
                String hex = Integer.toHexString(c).toUpperCase();
                if (hex.length() == 1)
                {
                    out.append('0');
                }
                out.append(hex);
            }
        }

        return out.toString();
    }

    private String makeBankCacheKey(String player, String hiscoreCategory)
    {
        String p = normalizePartForBankKey(player);
        String c = normalizePartForBankKey(hiscoreCategory);

        if (p == null || c == null)
        {
            return null;
        }

        return p + BANK_KEY_SEP + c;
    }

    private void saveLastKnownBankTotalForProfile(String player, String hiscoreCategory, long total)
    {
        String keySuffix = makeBankCacheKey(player, hiscoreCategory);
        if (keySuffix == null)
        {
            return;
        }

        configManager.setConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_LAST_BANK_TOTAL_PREFIX + keySuffix,
            Long.toString(total)
        );
    }

    private Long loadLastKnownBankTotalForProfile(String player, String hiscoreCategory)
    {
        String keySuffix = makeBankCacheKey(player, hiscoreCategory);
        if (keySuffix == null)
        {
            return null;
        }

        String raw = configManager.getConfiguration(
            CONFIG_GROUP,
            CONFIG_KEY_LAST_BANK_TOTAL_PREFIX + keySuffix
        );

        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }

        try
        {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException ex)
        {
            log.info("Invalid saved bank total for player '{}' category '{}': {}", player, hiscoreCategory, raw);
            return null;
        }
    }

    private void recordToastLevelUp(Skill skill,
                                    int fromLevel,
                                    int toLevel,
                                    int prevTotalLevel,
                                    int totalLevelAfter)
    {
        Integer idObj = SKILL_TO_ID.get(skill);
        if (idObj == null)
        {
            return;
        }
        final int id = idObj;

        synchronized (toastAggLock)
        {
            ToastAggEvent ev = toastAggEvents.get(skill);
            if (ev == null)
            {
                toastAggEvents.put(skill, new ToastAggEvent(skill, id, fromLevel, toLevel));
            }
            else
            {
                ev.to = Math.max(ev.to, toLevel);
            }

            // LEVEL_99 detection: crossing 99 within the aggregation window
            if (fromLevel < 99 && toLevel >= 99)
            {
                String name = (skill.getName() != null) ? skill.getName() : skill.toString();
                toastAggHit99.add(name);
            }

            // TOTAL LEVEL MILESTONE detection: crossing configured thresholds
            if (prevTotalLevel >= 0 && totalLevelAfter >= 0)
            {
                for (int m : TOTAL_LEVEL_MILESTONES)
                {
                    if (prevTotalLevel < m && totalLevelAfter >= m)
                    {
                        if (m > toastAggTotalMilestone)
                        {
                            toastAggTotalMilestone = m;
                            toastAggTotalAtMilestone = totalLevelAfter;
                        }
                        else if (m == toastAggTotalMilestone && totalLevelAfter > toastAggTotalAtMilestone)
                        {
                            toastAggTotalAtMilestone = totalLevelAfter;
                        }
                    }
                }
            }

            // MAX_TOTAL detection
            if (totalLevelAfter >= MAX_TOTAL_LEVEL)
            {
                toastAggSawMaxTotal = true;
            }
        }
    }


    private void scheduleToastAggFlush()
    {
        synchronized (toastAggLock)
        {
            if (toastAggFuture != null)
            {
                toastAggFuture.cancel(false);
                toastAggFuture = null;
            }

            toastAggFuture = executor.schedule(() ->
                clientThread.invokeLater(this::flushToastAggOnClientThread)
            , TOAST_AGG_WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushToastAggOnClientThread()
    {
        final java.util.ArrayList<ToastAggEvent> events;
        final java.util.ArrayList<String> hit99;
        final boolean sawMax;

        final int hitTotalMilestone;
        final int totalAtMilestone;

        synchronized (toastAggLock)
        {
            toastAggFuture = null;

            events = new java.util.ArrayList<>(toastAggEvents.values());
            hit99 = new java.util.ArrayList<>(toastAggHit99);
            sawMax = toastAggSawMaxTotal;

            hitTotalMilestone = toastAggTotalMilestone;
            totalAtMilestone  = toastAggTotalAtMilestone;

            toastAggEvents.clear();
            toastAggHit99.clear();
            toastAggSawMaxTotal = false;

            toastAggTotalMilestone = 0;
            toastAggTotalAtMilestone = 0;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        int totalLevel = client.getTotalLevel();
        long totalXp = computeTotalXp();

        lastKnownTotalLevel = totalLevel;
        lastKnownTotalXp = totalXp;

        // Collapse rules (exact order):
        // 1) MAX_TOTAL -> ONLY MAX_TOTAL
        if (sawMax || totalLevel >= MAX_TOTAL_LEVEL)
        {
            ToastItem[] toasts = new ToastItem[] {
                ToastItem.structured("MAX_TOTAL", 100, 10000, null, null, MAX_TOTAL_LEVEL)
            };
            PushPayload p = PushPayload.toastBatch(player, nextSeq(), true, toasts, totalLevel, totalXp);
            enqueue(PendingKind.TOAST, url, p);
            return;
        }


        java.util.ArrayList<ToastItem> milestoneOut = new java.util.ArrayList<>();

        // Any LEVEL_99
        for (String name : hit99)
        {
            milestoneOut.add(ToastItem.structured("LEVEL_99", 80, 10000, name, 99, null));
        }

        // Any TOTAL_LEVEL milestone
        if (hitTotalMilestone > 0)
        {
            // For TOTAL_LEVEL: use level=milestone, total=actual current total (may be > milestone)
            int actual = (totalAtMilestone > 0) ? totalAtMilestone : totalLevel;
            milestoneOut.add(ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, hitTotalMilestone, actual));
        }

        if (!milestoneOut.isEmpty())
        {
            ToastItem[] toasts = milestoneOut.toArray(new ToastItem[0]);
            PushPayload p = PushPayload.toastBatch(player, nextSeq(), true, toasts, totalLevel, totalXp);
            enqueue(PendingKind.TOAST, url, p);
            return;
        }


        // 3) Else sequential LEVEL_UP toasts (cap + summary)
        if (events.isEmpty())
        {
            return;
        }

        java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>();

        int emitted = 0;
        int remaining = 0;

        for (ToastAggEvent ev : events)
        {
            int from = ev.from;
            int to = ev.to;
            int delta = to - from;
            if (delta <= 0)
            {
                continue;
            }

            if (emitted < TOAST_LEVELUP_CAP)
            {
                String name = (ev.skill.getName() != null) ? ev.skill.getName() : ev.skill.toString();

                // Send only the final level; ESP32 formats the full message text
                out.add(ToastItem.structured("LEVEL_UP", 40, 6000, name, to, null));
                emitted++;
            }
            else
            {
                remaining++;
            }
        }

        if (remaining > 0)
        {
            out.add(ToastItem.of("OTHER", 10, 6000, "" + remaining + " more level-ups"));
        }

        if (out.isEmpty())
        {
            return;
        }

        ToastItem[] toasts = out.toArray(new ToastItem[0]);
        PushPayload p = PushPayload.toastBatch(player, nextSeq(), false, toasts, totalLevel, totalXp);
        enqueue(PendingKind.TOAST, url, p);
    }



    // --------------------
    // Test utilities
    // --------------------
    private void doPing()
    {
        HttpUrl url = buildUrl("ping");
        if (url == null)
        {
            uiSetPanelStatus("Ping failed: invalid device URL.");
            return;
        }

        uiSetPanelStatus("Pinging device...");

        log.info("AUTO CAT probe URL: {}", url);
        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException ex)
            {
                log.info("ESP32 ping failed: {}", ex.toString());
                uiSetPanelStatus("Ping failed.");
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException
            {
                try (Response r = resp)
                {
                    log.info("ESP32 ping HTTP {}", r.code());

                    if (r.isSuccessful())
                    {
                        uiSetPanelStatus("Ping OK (HTTP " + r.code() + ")");
                    }
                    else
                    {
                        uiSetPanelStatus("Ping failed (HTTP " + r.code() + ")");
                    }
                }
            }
        });
    }

    private void testToast99AndTotal()
{
    // Matches your desired order: 99 first, then milestone
    sendToastBatch(true, new ToastItem[] {
        ToastItem.structured("LEVEL_99", 80, 10000, "Agility", 99, null),
        ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 1000, 1000)
    });
}

private void testToast99AndMulti(int n)
{
    if (n < 1) n = 1;
    if (n > 24) n = 24;

    // 99 skill (Agility). Then N level-ups, explicitly excluding Agility to validate no redundancy.
    java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(1 + n);
    out.add(ToastItem.structured("LEVEL_99", 80, 10000, "Agility", 99, null));

    Skill[] skills = SKILL_TO_ID.keySet().toArray(new Skill[0]);

    int added = 0;
    for (int i = 0; i < skills.length && added < n; i++)
    {
        Skill s = skills[i];
        if (s == Skill.AGILITY) continue;

        String name = skillName(s);
        int level = 10 + (added % 80);
        out.add(ToastItem.structured("LEVEL_UP", 40, 6000, name, level, null));
        added++;
    }

    sendToastBatch(true, out.toArray(new ToastItem[0]));
    }

    private void testToastTotalAndMulti(int n)
    {
        if (n < 1) n = 1;
        if (n > 24) n = 24;

        java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(1 + n);
        out.add(ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 1000, 1000));

        Skill[] skills = SKILL_TO_ID.keySet().toArray(new Skill[0]);

        for (int i = 0; i < n; i++)
        {
            Skill s = skills[i % skills.length];
            String name = skillName(s);
            int level = 10 + (i % 80);
            out.add(ToastItem.structured("LEVEL_UP", 40, 6000, name, level, null));
        }

        sendToastBatch(true, out.toArray(new ToastItem[0]));
    }

    private void testToast99TotalMulti(int n)
    {
        if (n < 1) n = 1;
        if (n > 24) n = 24;

        java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(2 + n);

        // Desired order: 99 -> total -> level-ups (excluding the 99 skill)
        out.add(ToastItem.structured("LEVEL_99", 80, 10000, "Agility", 99, null));
        out.add(ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 1000, 1000));

        Skill[] skills = SKILL_TO_ID.keySet().toArray(new Skill[0]);

        int added = 0;
        for (int i = 0; i < skills.length && added < n; i++)
        {
            Skill s = skills[i];
            if (s == Skill.AGILITY) continue;

            String name = skillName(s);
            int level = 10 + (added % 80);
            out.add(ToastItem.structured("LEVEL_UP", 40, 6000, name, level, null));
            added++;
        }

        sendToastBatch(true, out.toArray(new ToastItem[0]));
    }


    private void sendTestToast()
    {
        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        ToastItem[] toasts = new ToastItem[] {
            ToastItem.of("OTHER", 10, 2500, "TEST PUSH RECEIVED")
        };
        PushPayload p = PushPayload.toastBatch(player, nextSeq(), false, toasts, lastKnownTotalLevel >= 0 ? lastKnownTotalLevel : null,
            lastKnownTotalXp >= 0 ? lastKnownTotalXp : null);
        enqueue(PendingKind.TOAST, url, p);

    }

    private void sendToastBatch(boolean flushLower, ToastItem[] toasts)
    {
        if (!canSendLiveForCurrentLogin())
        {
            return;
        }

        String player = getPushPlayer();
        HttpUrl url = buildUrl("push");
        if (player == null || url == null)
        {
            return;
        }

        Integer tl = null;
        Long txp = null;

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            tl = client.getTotalLevel();
            txp = computeTotalXp();
            lastKnownTotalLevel = tl;
            lastKnownTotalXp = txp;
        }
        else
        {
            if (lastKnownTotalLevel >= 0) tl = lastKnownTotalLevel;
            if (lastKnownTotalXp >= 0) txp = lastKnownTotalXp;
        }

        PushPayload p = PushPayload.toastBatch(player, nextSeq(), flushLower, toasts, tl, txp);
        enqueue(PendingKind.TOAST, url, p);
    }

    private void scheduleClientToast(long delayMs, Runnable r)
    {
        executor.schedule(() -> clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                return;
            }
            r.run();
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private static String skillName(Skill s)
    {
        return (s.getName() != null) ? s.getName() : s.toString();
    }

    // ---- Specific tests ----

    private void testToastOneLevelUp()
    {
        ToastItem[] t = new ToastItem[] {
            ToastItem.structured("LEVEL_UP", 40, 6000, "Attack", 2, null)
        };
        sendToastBatch(false, t);
    }

    private void testToast99()
    {
        ToastItem[] t = new ToastItem[] {
            ToastItem.structured("LEVEL_99", 80, 10000, "Slayer", 99, null)
        };
        sendToastBatch(true, t);
    }

    private void testToastTotalMilestone()
    {
        // level=milestone, total=current (for formatting)
        ToastItem[] t = new ToastItem[] {
            ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 1000, 1000)
        };
        sendToastBatch(true, t);
    }

    private void testToastMultiLevelUps(int n)
    {
        if (n < 1) n = 1;
        if (n > 24) n = 24;

        java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(n);
        Skill[] skills = SKILL_TO_ID.keySet().toArray(new Skill[0]);

        for (int i = 0; i < n; i++)
        {
            Skill s = skills[i % skills.length];
            String name = skillName(s);

            int level = 10 + (i % 80); // arbitrary but plausible
            out.add(ToastItem.structured("LEVEL_UP", 40, 3500, name, level, null));
        }

        sendToastBatch(false, out.toArray(new ToastItem[0]));
    }

    private void testToastLevelUpThen99()
    {
        // Exercise ESP32 preemption: low prio first, then high milestone
        sendToastBatch(false, new ToastItem[] {
            ToastItem.structured("LEVEL_UP", 40, 10000, "Cooking", 50, null)
        });

        scheduleClientToast(350, () -> sendToastBatch(true, new ToastItem[] {
            ToastItem.structured("LEVEL_99", 80, 10000, "Cooking", 99, null)
        }));
    }

    private void testToastLevelUpThenTotalMilestone()
    {
        sendToastBatch(false, new ToastItem[] {
            ToastItem.structured("LEVEL_UP", 40, 10000, "Fishing", 60, null)
        });

        scheduleClientToast(350, () -> sendToastBatch(true, new ToastItem[] {
            ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 1500, 1500)
        }));
    }

    private void testToastLevelUpThen99ThenMax()
    {
        sendToastBatch(false, new ToastItem[] {
            ToastItem.structured("LEVEL_UP", 40, 10000, "Agility", 70, null)
        });

        scheduleClientToast(350, () -> sendToastBatch(true, new ToastItem[] {
            ToastItem.structured("LEVEL_99", 80, 10000, "Agility", 99, null)
        }));

        scheduleClientToast(700, () -> sendToastBatch(true, new ToastItem[] {
            ToastItem.structured("MAX_TOTAL", 100, 10000, null, null, MAX_TOTAL_LEVEL)
        }));
    }

    // Recommended extras:

    private void testToastQueueOverflow()
    {
        java.util.ArrayList<ToastItem> out = new java.util.ArrayList<>(20);
        for (int i = 1; i <= 20; i++)
        {
            out.add(ToastItem.of("OTHER", 10, 1500, "QUEUE TEST " + i));
        }
        sendToastBatch(false, out.toArray(new ToastItem[0]));
    }

    private void testToastPreemptLong()
    {
        // Long low-prio toast, then preempt with TOTAL_LEVEL (milestone)
        sendToastBatch(false, new ToastItem[] {
            ToastItem.of("OTHER", 10, 10000, "LOW PRIO LONG TOAST (should be preempted)")
        });

        scheduleClientToast(500, () -> sendToastBatch(true, new ToastItem[] {
            ToastItem.structured("TOTAL_LEVEL", 70, 9000, null, 2000, 2000)
        }));
    }


    // --------------------
    // Bank total compute
    // --------------------
    private long computeBankTotal()
    {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank == null)
        {
            return (lastSentBankTotal >= 0) ? lastSentBankTotal : 0;
        }

        long total = 0;
        for (Item it : bank.getItems())
        {
            if (it == null) continue;
            int id = it.getId();
            int qty = it.getQuantity();
            if (id <= 0 || qty <= 0) continue;

            int canon = itemManager.canonicalize(id);
            if (canon <= 0) canon = id;
            int price = itemManager.getItemPrice(canon);
            total += (long) price * (long) qty;
        }
        return total;
    }

    // --------------------
    // Coalescing sender with retry/backoff (skills/bank)
    // --------------------
    private void enqueue(PendingKind kind, HttpUrl url, PushPayload payload)
    {
        synchronized (sendLock)
        {
            pending.put(kind, payload);

            if (retryFuture == null && inFlightPayload == null)
            {
                sendNextLocked();
            }
        }
    }

    private void sendNextLocked()
    {
        if (inFlightPayload != null)
        {
            return;
        }

        PendingKind nextKind = pickNextKindLocked();
        if (nextKind == null)
        {
            return;
        }

        PushPayload p = pending.get(nextKind);
        if (p == null)
        {
            return;
        }

        HttpUrl url = buildUrl("push");
        if (url == null)
        {
            scheduleRetryLocked();
            return;
        }

        inFlightKind = nextKind;
        inFlightPayload = p;

        log.info("SEND START kind={}", nextKind);
        postAsync(url, p);
    }

    private PendingKind pickNextKindLocked()
    {
        if (pending.containsKey(PendingKind.CONFIG)) return PendingKind.CONFIG;
        if (pending.containsKey(PendingKind.TOAST)) return PendingKind.TOAST;
        if (pending.containsKey(PendingKind.SNAPSHOT)) return PendingKind.SNAPSHOT;
        if (pending.containsKey(PendingKind.LEVEL)) return PendingKind.LEVEL;
        if (pending.containsKey(PendingKind.BANK))  return PendingKind.BANK;
        if (pending.containsKey(PendingKind.XP))    return PendingKind.XP;
        if (pending.containsKey(PendingKind.HEARTBEAT)) return PendingKind.HEARTBEAT;
        return null;
    }


    private void postAsync(HttpUrl url, PushPayload payload)
    {
        String json = gson.toJson(payload);
        RequestBody body = RequestBody.create(JSON, json);
        Request req = new Request.Builder().url(url).post(body).build();

        http.newCall(req).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException ex)
            {
                onSendResult(false, 0, ex.toString());
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException
            {
                int code;
                try (Response r = resp)
                {
                    code = r.code();
                }
                boolean ok = (code >= 200 && code < 300);
                onSendResult(ok, code, null);
            }
        });
    }

    private void onSendResult(boolean ok, int httpCode, String err)
    {
        synchronized (sendLock)
        {
            PendingKind sentKind = inFlightKind;
            PushPayload sentPayload = inFlightPayload;

            inFlightKind = null;
            inFlightPayload = null;

            if (ok)
            {
                log.info("SEND OK kind={} httpCode={}", sentKind, httpCode);

                PushPayload stillPending = pending.get(sentKind);
                if (stillPending == sentPayload)
                {
                    pending.remove(sentKind);
                }

                consecutiveFailures = 0;
                backoffMs = RETRY_BACKOFF_BASE_MS;

                if (retryFuture != null)
                {
                    retryFuture.cancel(false);
                    retryFuture = null;
                }

                sendNextLocked();
                return;
            }

            boolean nonRetryable403 =
                httpCode == 403
                    && sentKind != PendingKind.CONFIG;

            if (nonRetryable403)
            {
                PushPayload stillPending = pending.get(sentKind);
                if (stillPending == sentPayload)
                {
                    pending.remove(sentKind);
                }

                consecutiveFailures = 0;
                backoffMs = RETRY_BACKOFF_BASE_MS;

                log.info("ESP32 push rejected with HTTP 403 for {}. Dropping without retry.", sentKind);

                sendNextLocked();
                return;
            }

            consecutiveFailures++;
            if (httpCode != 0)
            {
                log.info("ESP32 push failed HTTP {}", httpCode);
            }
            else if (err != null)
            {
                log.info("ESP32 push failed: {}", err);
            }

            backoffMs = Math.min(backoffMs * 2, RETRY_BACKOFF_MAX_MS);
            scheduleRetryLocked();
        }
    }

    private void scheduleRetryLocked()
    {
        if (retryFuture != null && !retryFuture.isDone())
        {
            return;
        }

        long delay = backoffMs;
        retryFuture = executor.schedule(() ->
        {
            synchronized (sendLock)
            {
                retryFuture = null;
                if (inFlightPayload == null)
                {
                    sendNextLocked();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static final int MAX_TOTAL_LEVEL = 99 * SKILL_TO_ID.size();


}
