package com.tytoowns.osrstrackerbridge;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.PluginPanel;
import javax.swing.JList;
import javax.swing.text.DefaultCaret;

public class ScreenOfKnowledgeBridgePanel extends PluginPanel
{
    private final ScreenOfKnowledgeBridgePlugin plugin;

    private final JComboBox<OsrsTrackerBridgeConfig.DisplayMode> displayModeDropdown;
    private final JTextField pinnedPlayerField;
    private final JComboBox<OsrsTrackerBridgeConfig.GameModeChoice> pinnedGameModeDropdown;
    private final javax.swing.JCheckBox apiOnlyCheckbox;

    private final JPanel displayModeLabelRow;
    private final JPanel displayModeFieldRow;

    private final JPanel pinnedPlayerLabelRow;
    private final JPanel pinnedPlayerFieldRow;
    private final JPanel pinnedGameModeLabelRow;
    private final JPanel pinnedGameModeFieldRow;

    private final JTextArea apiOnlyDescription;
    private final JTextArea displayModeDescription;

    private final JTextArea deviceModeValueLabel;
    private final JTextArea deviceTargetValueLabel;
    private final JTextArea deviceVisibleValueLabel;
    private final JTextArea deviceLiveOwnerValueLabel;
    private final JTextArea deviceHeartbeatFreshValueLabel;
    private final JTextArea deviceDisplayAllowedValueLabel;

    private final JTextArea currentPlayerValueLabel;
    private final JTextArea currentHiscoreCategoryValueLabel;
    private final JTextArea currentModeValueLabel;

    private final JTextArea statusLabel;
    private boolean suppressEvents = false;
    public ScreenOfKnowledgeBridgePanel(ScreenOfKnowledgeBridgePlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel root = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(0, 0, 8, 0);

        // Title
        root.add(makeTitleLabel("Screen Of Knowledge Bridge"), gbc);

        // --------------------
        // Quick Action: current logged-in player
        // --------------------
        gbc.gridy++;
        root.add(makeSeparator(), gbc);

        gbc.gridy++;
        root.add(makeSectionHeader("Quick Set"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Use your currently logged-in RuneLite character and its current game mode."
        ), gbc);

        gbc.gridy++;
        JButton setLoggedInButton = new JButton("Set to currently logged in player");
        setLoggedInButton.addActionListener(e -> plugin.uiSetToLoggedInPlayerNow());
        root.add(setLoggedInButton, gbc);

        // --------------------
        // Manual device target section
        // --------------------
        gbc.gridy++;
        root.add(makeSeparator(), gbc);

        gbc.gridy++;
        root.add(makeSectionHeader("Device Mode / Profile"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Choose how the device selects a profile, or enable API-only mode to lock the device to a static player/game mode and ignore live plugin data."
        ), gbc);

        gbc.gridy++;
        apiOnlyCheckbox = new javax.swing.JCheckBox("API-only mode");
        apiOnlyCheckbox.setOpaque(false);
        apiOnlyCheckbox.setForeground(Color.WHITE);
        apiOnlyCheckbox.addActionListener(e ->
        {
            if (suppressEvents)
            {
                return;
            }

            plugin.uiSetApiOnly(apiOnlyCheckbox.isSelected());
            updateDeviceConfigVisibility();
        });
        root.add(apiOnlyCheckbox, gbc);

        gbc.gridy++;
        apiOnlyDescription = makeSectionDescription("");
        root.add(apiOnlyDescription, gbc);

        gbc.gridy++;
        displayModeLabelRow = wrapRow(makeFieldLabel("Display mode"));
        root.add(displayModeLabelRow, gbc);

        gbc.gridy++;
        displayModeDropdown = new JComboBox<>(OsrsTrackerBridgeConfig.DisplayMode.values());
        displayModeDropdown.setRenderer((list, value, index, isSelected, cellHasFocus) ->
        {
            JLabel label = new JLabel(displayModeText(value));
            label.setOpaque(true);

            if (isSelected)
            {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            else
            {
                label.setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }

            return label;
        });
        displayModeDropdown.addActionListener(e ->
        {
            if (suppressEvents)
            {
                return;
            }

            OsrsTrackerBridgeConfig.DisplayMode mode =
                (OsrsTrackerBridgeConfig.DisplayMode) displayModeDropdown.getSelectedItem();

            plugin.uiSetDisplayMode(mode);
            updateDeviceConfigVisibility();
        });
        displayModeFieldRow = wrapRow(displayModeDropdown);
        root.add(displayModeFieldRow, gbc);

        gbc.gridy++;
        displayModeDescription = makeSectionDescription("");
        root.add(displayModeDescription, gbc);

        gbc.gridy++;
        pinnedPlayerLabelRow = wrapRow(makeFieldLabel("Pinned player"));
        root.add(pinnedPlayerLabelRow, gbc);

        gbc.gridy++;
        pinnedPlayerField = new JTextField();
        pinnedPlayerField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                push();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                push();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                push();
            }

            private void push()
            {
                if (suppressEvents)
                {
                    return;
                }

                plugin.uiSetPinnedPlayer(pinnedPlayerField.getText());
            }
        });
        pinnedPlayerFieldRow = wrapRow(pinnedPlayerField);
        root.add(pinnedPlayerFieldRow, gbc);

        gbc.gridy++;
        pinnedGameModeLabelRow = wrapRow(makeFieldLabel("Pinned game mode"));
        root.add(pinnedGameModeLabelRow, gbc);

        gbc.gridy++;
        pinnedGameModeDropdown = new JComboBox<>(OsrsTrackerBridgeConfig.GameModeChoice.values());
        pinnedGameModeDropdown.setRenderer((list, value, index, isSelected, cellHasFocus) ->
        {
            JLabel label = new JLabel(gameModeText(value));
            label.setOpaque(true);

            if (isSelected)
            {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            else
            {
                label.setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }

            return label;
        });
        pinnedGameModeDropdown.addActionListener(e ->
        {
            if (suppressEvents)
            {
                return;
            }

            OsrsTrackerBridgeConfig.GameModeChoice choice =
                (OsrsTrackerBridgeConfig.GameModeChoice) pinnedGameModeDropdown.getSelectedItem();

            plugin.uiSetPinnedGameMode(choice);
        });
        pinnedGameModeFieldRow = wrapRow(pinnedGameModeDropdown);
        root.add(pinnedGameModeFieldRow, gbc);

        gbc.gridy++;
        JButton applyModeProfileButton = new JButton("Save and apply settings to device");
        applyModeProfileButton.addActionListener(e -> plugin.uiApplyModeProfileNow());
        root.add(applyModeProfileButton, gbc);

        gbc.gridy++;
        root.add(makeSeparator(), gbc);

        // --------------------
        // Connection / Testing
        // --------------------

        gbc.gridy++;
        root.add(makeSectionHeader("Connection / Testing"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Check connectivity and send example pushes to verify the device is responding."
        ), gbc);

        gbc.gridy++;
        JButton pingButton = new JButton("Test connection (/ping)");
        pingButton.addActionListener(e -> plugin.uiTestPing());
        root.add(pingButton, gbc);

        gbc.gridy++;
        JButton testPushButton = new JButton("Send test push");
        testPushButton.addActionListener(e -> plugin.uiSendTestPush());
        root.add(testPushButton, gbc);

        gbc.gridy++;
        JButton toastPresetButton = new JButton("Send selected toast test");
        toastPresetButton.addActionListener(e -> plugin.uiSendSelectedToastTest());
        root.add(toastPresetButton, gbc);

        gbc.gridy++;
        statusLabel = makeWrappingValueText("Status: idle", Color.WHITE);
        root.add(wrapRow(statusLabel), gbc);

        gbc.gridy++;
        root.add(makeSeparator(), gbc);

        gbc.gridy++;
        root.add(makeSectionHeader("Device Status"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Live status read from the device /status endpoint."
        ), gbc);

        gbc.gridy++;
        deviceModeValueLabel = makeValueLabel("Mode: -");
        root.add(wrapRow(deviceModeValueLabel), gbc);

        gbc.gridy++;
        deviceTargetValueLabel = makeValueLabel("Target: -");
        root.add(wrapRow(deviceTargetValueLabel), gbc);

        gbc.gridy++;
        deviceVisibleValueLabel = makeValueLabel("Visible: -");
        root.add(wrapRow(deviceVisibleValueLabel), gbc);

        gbc.gridy++;
        deviceLiveOwnerValueLabel = makeValueLabel("Live owner: -");
        root.add(wrapRow(deviceLiveOwnerValueLabel), gbc);

        gbc.gridy++;
        deviceHeartbeatFreshValueLabel = makeValueLabel("Plugin heartbeat fresh: -");
        root.add(wrapRow(deviceHeartbeatFreshValueLabel), gbc);
        gbc.gridy++;
        deviceDisplayAllowedValueLabel = makeValueLabel("Plugin display allowed: -");
        root.add(wrapRow(deviceDisplayAllowedValueLabel), gbc);

        gbc.gridy++;
        root.add(makeSeparator(), gbc);

        gbc.gridy++;
        root.add(makeSectionHeader("Current RuneLite Player"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Live info derived locally from your currently logged-in RuneLite client."
        ), gbc);

        gbc.gridy++;
        currentPlayerValueLabel = makeValueLabel("Current player: -");
        root.add(wrapRow(currentPlayerValueLabel), gbc);

        gbc.gridy++;
        currentHiscoreCategoryValueLabel = makeValueLabel("Current hiscore category: -");
        root.add(wrapRow(currentHiscoreCategoryValueLabel), gbc);

        gbc.gridy++;
        currentModeValueLabel = makeValueLabel("Mode: -");
        root.add(wrapRow(currentModeValueLabel), gbc);

        // filler
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        root.add(new JPanel(), gbc);

        add(root, BorderLayout.CENTER);

        refreshFromPlugin();
    }

    public void refreshFromPlugin()
    {
        suppressEvents = true;
        try
        {
            displayModeDropdown.setSelectedItem(plugin.uiGetDisplayMode());
            pinnedPlayerField.setText(plugin.uiGetPinnedPlayer());
            pinnedGameModeDropdown.setSelectedItem(plugin.uiGetPinnedGameMode());
            apiOnlyCheckbox.setSelected(plugin.uiIsApiOnly());
        }
        finally
        {
            suppressEvents = false;
        }

        currentPlayerValueLabel.setText("Current player: " + valueOrDash(plugin.uiGetCurrentPlayerName()));
        currentHiscoreCategoryValueLabel.setText("Current hiscore category: " + valueOrDash(plugin.uiGetCurrentHiscoreCategoryDisplay()));
        currentModeValueLabel.setText("Mode: " + valueOrDash(plugin.uiGetCurrentModeDisplay()));

        updateDeviceConfigVisibility();
        refreshDeviceStatusBlock();
    }

    public void setStatusText(String text)
    {
        statusLabel.setText("Status: " + (text == null || text.trim().isEmpty() ? "idle" : text));
    }

    public void refreshDeviceStatusBlock()
    {
        String mode = plugin.uiGetDeviceDisplayMode();
        String targetPlayer = plugin.uiGetDeviceTargetPlayer();
        String targetCategory = plugin.uiGetDeviceTargetCategory();
        String visiblePlayer = plugin.uiGetDeviceVisiblePlayer();
        String visibleCategory = plugin.uiGetDeviceVisibleCategory();
        String liveOwnerPlayer = plugin.uiGetDeviceLiveOwnerPlayer();
        String liveOwnerGameMode = plugin.uiGetDeviceLiveOwnerGameMode();
        String liveOwnerCategory = plugin.uiGetDeviceLiveOwnerCategory();
        Boolean heartbeatFresh = plugin.uiGetDevicePluginHeartbeatFresh();
        Boolean displayAllowed = plugin.uiGetDevicePluginDisplayAllowed();

        deviceModeValueLabel.setText("Mode: " + valueOrDash(mode));
        deviceTargetValueLabel.setText(
            "Target: " + pairOrDash(targetPlayer, targetCategory)
        );
        deviceVisibleValueLabel.setText(
            "Visible: " + pairOrDash(visiblePlayer, visibleCategory)
        );
        deviceLiveOwnerValueLabel.setText(
            "Live owner: " + tripleOrDash(liveOwnerPlayer, liveOwnerGameMode, liveOwnerCategory)
        );
        deviceHeartbeatFreshValueLabel.setText(
            "Plugin heartbeat fresh: " + boolOrDash(heartbeatFresh)
        );
        deviceDisplayAllowedValueLabel.setText(
            "Plugin display allowed: " + boolOrDash(displayAllowed)
        );

        currentPlayerValueLabel.setText("Current player: " + valueOrDash(plugin.uiGetCurrentPlayerName()));
        currentHiscoreCategoryValueLabel.setText("Current hiscore category: " + valueOrDash(plugin.uiGetCurrentHiscoreCategoryDisplay()));
        currentModeValueLabel.setText("Mode: " + valueOrDash(plugin.uiGetCurrentModeDisplay()));

        revalidate();
        repaint();
    }

    private JPanel wrapRow(java.awt.Component component)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(component, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private JTextArea makeValueLabel(String text)
    {
        return makeWrappingValueText(text, Color.LIGHT_GRAY);
    }

    private JTextArea makeWrappingValueText(String text, Color color)
    {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setForeground(color);
        area.setFont(new JLabel().getFont());

        DefaultCaret caret = (DefaultCaret) area.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        area.setCaretPosition(0);

        return area;
    }

    private String valueOrDash(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return "-";
        }
        return value.trim();
    }

    private String boolOrDash(Boolean value)
    {
        return value == null ? "-" : value.toString();
    }

    private String pairOrDash(String left, String right)
    {
        boolean hasLeft = left != null && !left.trim().isEmpty();
        boolean hasRight = right != null && !right.trim().isEmpty();

        if (!hasLeft && !hasRight)
        {
            return "-";
        }

        if (!hasLeft)
        {
            return right.trim();
        }

        if (!hasRight)
        {
            return left.trim();
        }

        return left.trim() + " / " + right.trim();
    }

    private String tripleOrDash(String first, String second, String third)
    {
        StringBuilder out = new StringBuilder();

        if (first != null && !first.trim().isEmpty())
        {
            out.append(first.trim());
        }

        if (second != null && !second.trim().isEmpty())
        {
            if (out.length() > 0)
            {
                out.append(" / ");
            }
            out.append(second.trim());
        }

        if (third != null && !third.trim().isEmpty())
        {
            if (out.length() > 0)
            {
                out.append(" / ");
            }
            out.append(third.trim());
        }

        return out.length() == 0 ? "-" : out.toString();
    }
    private void updateDeviceConfigVisibility()
    {
        boolean apiOnly = plugin.uiIsApiOnly();
        OsrsTrackerBridgeConfig.DisplayMode mode = plugin.uiGetDisplayMode();

        boolean showDisplayMode = !apiOnly;
        boolean showPinnedPlayer = false;
        boolean showPinnedGameMode = false;

        if (apiOnly)
        {
            showPinnedPlayer = true;
            showPinnedGameMode = true;
        }
        else
        {
            switch (mode)
            {
                case FIXED_PROFILE:
                    showPinnedPlayer = true;
                    showPinnedGameMode = true;
                    break;

                case FIXED_PLAYER_AUTO_CATEGORY:
                    showPinnedPlayer = true;
                    showPinnedGameMode = false;
                    break;

                case FOLLOW_ACTIVE_SOURCE:
                    showPinnedPlayer = false;
                    showPinnedGameMode = false;
                    break;

                default:
                    showPinnedPlayer = true;
                    showPinnedGameMode = false;
                    break;
            }
        }

        displayModeLabelRow.setVisible(showDisplayMode);
        displayModeFieldRow.setVisible(showDisplayMode);
        displayModeDescription.setVisible(showDisplayMode);

        pinnedPlayerLabelRow.setVisible(showPinnedPlayer);
        pinnedPlayerFieldRow.setVisible(showPinnedPlayer);
        pinnedGameModeLabelRow.setVisible(showPinnedGameMode);
        pinnedGameModeFieldRow.setVisible(showPinnedGameMode);

        apiOnlyDescription.setText(getApiOnlyDescriptionText());
        displayModeDescription.setText(getDisplayModeDescriptionText(mode));

        revalidate();
        repaint();
    }

    private String displayModeText(OsrsTrackerBridgeConfig.DisplayMode mode)
    {
        if (mode == null)
        {
            return "";
        }

        switch (mode)
        {
            case FIXED_PROFILE:
                return "Fixed profile";

            case FIXED_PLAYER_AUTO_CATEGORY:
                return "Fixed player, automatic category";

            case FOLLOW_ACTIVE_SOURCE:
                return "Follow active source";

            default:
                return mode.name();
        }
    }

    private String gameModeText(OsrsTrackerBridgeConfig.GameModeChoice mode)
    {
        if (mode == null)
        {
            return "";
        }

        switch (mode)
        {
            case NORMAL:
                return "Normal";
            case IRONMAN:
                return "Ironman";
            case HARDCORE_IRONMAN:
                return "Hardcore Ironman";
            case ULTIMATE_IRONMAN:
                return "Ultimate Ironman";
            case GROUP_IRONMAN:
                return "Group Ironman";
            case GROUP_HARDCORE_IRONMAN:
                return "Group Hardcore Ironman";
            case GROUP_UNRANKED_IRONMAN:
                return "Group Unranked Ironman";
            case LEAGUE:
                return "League";
            case DEADMAN:
                return "Deadman";
            case TOURNAMENT:
                return "Tournament";
            case FRESH_START:
                return "Fresh Start";
            default:
                return mode.name();
        }
    }

    private String getApiOnlyDescriptionText()
    {
        return "API-only mode makes the device use only the saved player/game mode and OSRS hiscores API data. Live plugin stats, heartbeats, and toasts are ignored.";
    }

    private String getDisplayModeDescriptionText(OsrsTrackerBridgeConfig.DisplayMode mode)
    {
        if (mode == null)
        {
            return "";
        }

        switch (mode)
        {
            case FIXED_PROFILE:
                return "Always show one specific player and one specific game mode.";

            case FIXED_PLAYER_AUTO_CATEGORY:
                return "Always show one specific player, but automatically detect the game mode from the logged-in RuneLite account.";

            case FOLLOW_ACTIVE_SOURCE:
                return "The device follows whichever valid RuneLite client becomes the active live source.";

            default:
                return "";
        }
    }

    private JLabel makeTitleLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1.0f));
        return label;
    }

    private JLabel makeSectionHeader(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private JTextArea makeSectionDescription(String text)
    {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setForeground(Color.LIGHT_GRAY);
        area.setFont(new JLabel().getFont());
        return area;
    }

    private JLabel makeFieldLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        return label;
    }

    private JPanel makeSeparator()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY));
        return panel;
    }
}
