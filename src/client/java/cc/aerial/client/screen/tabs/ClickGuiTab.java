package cc.aerial.client.screen.tabs;

/**
 * The top-level tabs shown by the ClickGui tab bar, echoleak-style:
 * the regular module GUI, the script constructor, and the config manager.
 */
public enum ClickGuiTab {
    FEATURES("Features", TabIcons.FEATURES),
    SCRIPTS("Scripts", TabIcons.SCRIPTS),
    HUD("HUD", TabIcons.HUD),
    CONFIGS("Configs", TabIcons.CONFIGS);

    public final String label;
    public final char icon;

    ClickGuiTab(String label, char icon) {
        this.label = label;
        this.icon = icon;
    }

    public static final ClickGuiTab[] VALUES = values();
}
