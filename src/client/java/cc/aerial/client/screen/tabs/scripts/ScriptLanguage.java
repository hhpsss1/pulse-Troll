package cc.aerial.client.screen.tabs.scripts;

/** Languages supported by the advanced script editor. */
public enum ScriptLanguage {
    JAVASCRIPT("JavaScript", "js"),
    LUA("Lua", "lua");

    public final String display;
    public final String ext;

    ScriptLanguage(String display, String ext) {
        this.display = display;
        this.ext = ext;
    }

    public ScriptLanguage next() {
        return this == JAVASCRIPT ? LUA : JAVASCRIPT;
    }

    public static ScriptLanguage forFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".lua") ? LUA : JAVASCRIPT;
    }

    public static final ScriptLanguage[] VALUES = values();
}
