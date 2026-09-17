package cc.aerial.client.screen.tabs;

/**
 * Material-icon glyph code points used by the ClickGui tab bar and the tab panels.
 * These must be registered in {@code AerialClickGui.ensureFontsLoaded()} so the outlined
 * icon font atlas rasterizes them; otherwise they render blank.
 */
public final class TabIcons {
    private TabIcons() {
    }

    // Tab bar
    public static final char FEATURES = 0xE5C3; // apps
    public static final char SCRIPTS = 0xE86F;  // code
    public static final char CONFIGS = 0xE2C7;  // folder
    public static final char HUD = 0xE871;      // dashboard

    // Actions used by the tab panels
    public static final char ADD = 0xE145;      // add
    public static final char PLAY = 0xE037;     // play_arrow
    public static final char SAVE = 0xE161;     // save
    public static final char TRASH = 0xE872;    // delete
    public static final char DOC = 0xE873;      // description
    public static final char REFRESH = 0xE5D5;  // refresh
    public static final char NEW = 0xE89C;      // note_add
    public static final char CHEVRON = 0xE5CF;  // expand_more
    public static final char COPY = 0xE14D;     // content_copy
    public static final char UP = 0xE5D8;       // arrow_upward
    public static final char DOWN = 0xE5DB;     // arrow_downward
    public static final char CODE = 0xE86F;     // code
}
