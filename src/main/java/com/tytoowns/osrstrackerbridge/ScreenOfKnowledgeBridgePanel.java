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

public class ScreenOfKnowledgeBridgePanel extends PluginPanel
{
    private final ScreenOfKnowledgeBridgePlugin plugin;

    private final JTextField playerOverrideField;
    private final JComboBox<OsrsTrackerBridgeConfig.HiscoreCategoryChoice> hiscoreCategoryDropdown;
    private final JLabel statusLabel;

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
            "Use your currently logged-in RuneLite character and AUTO hiscore category."
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
        root.add(makeSectionHeader("Manual Device Target"), gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "Manually choose which player and hiscore category the device should target."
        ), gbc);

        gbc.gridy++;
        root.add(makeFieldLabel("Player override"), gbc);

        gbc.gridy++;
        playerOverrideField = new JTextField();
        playerOverrideField.getDocument().addDocumentListener(new DocumentListener()
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

                plugin.uiSetPlayerOverride(playerOverrideField.getText());
            }
        });
        root.add(playerOverrideField, gbc);

        gbc.gridy++;
        root.add(makeFieldLabel("Hiscore category"), gbc);

        gbc.gridy++;
        hiscoreCategoryDropdown = new JComboBox<>(OsrsTrackerBridgeConfig.HiscoreCategoryChoice.values());
        hiscoreCategoryDropdown.addActionListener(e ->
        {
            if (suppressEvents)
            {
                return;
            }

            OsrsTrackerBridgeConfig.HiscoreCategoryChoice choice =
                (OsrsTrackerBridgeConfig.HiscoreCategoryChoice) hiscoreCategoryDropdown.getSelectedItem();

            plugin.uiSetHiscoreCategory(choice);
        });
        root.add(hiscoreCategoryDropdown, gbc);

        gbc.gridy++;
        root.add(makeSectionDescription(
            "These fields do not push automatically. Click the button below to apply them to the device."
        ), gbc);

        gbc.gridy++;
        JButton pushConfigButton = new JButton("Push player/category now");
        pushConfigButton.addActionListener(e -> plugin.uiPushResolvedConfigNow());
        root.add(pushConfigButton, gbc);

        // --------------------
        // Connection / Testing
        // --------------------
        gbc.gridy++;
        root.add(makeSeparator(), gbc);

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
        statusLabel = new JLabel("Status: idle");
        statusLabel.setForeground(Color.WHITE);
        root.add(statusLabel, gbc);

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
            playerOverrideField.setText(plugin.uiGetPlayerOverride());
            hiscoreCategoryDropdown.setSelectedItem(plugin.uiGetHiscoreCategoryChoice());
        }
        finally
        {
            suppressEvents = false;
        }
    }

    public void setStatusText(String text)
    {
        statusLabel.setText("Status: " + (text == null || text.trim().isEmpty() ? "idle" : text));
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
