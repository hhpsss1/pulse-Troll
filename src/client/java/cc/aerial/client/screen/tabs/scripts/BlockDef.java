package cc.aerial.client.screen.tabs.scripts;

import java.util.List;

/**
 * A template for a visual-constructor block. A block is either a trigger (which lifecycle hook to
 * bind), a condition (a boolean JS expression) or an action (a JS statement). {@code label} and
 * {@code template} may contain {@code {0}}, {@code {1}} … placeholders filled from {@link #params}.
 */
public final class BlockDef {
    public enum Kind {TRIGGER, CONDITION, ACTION}

    public enum ParamType {TEXT, NUMBER}

    public record Param(String name, ParamType type, String defaultValue) {
    }

    public final String id;
    public final Kind kind;
    public final String label;
    public final String template;
    public final List<Param> params;

    public BlockDef(String id, Kind kind, String label, String template, Param... params) {
        this.id = id;
        this.kind = kind;
        this.label = label;
        this.template = template;
        this.params = List.of(params);
    }

    public static Param text(String name, String def) {
        return new Param(name, ParamType.TEXT, def);
    }

    public static Param number(String name, String def) {
        return new Param(name, ParamType.NUMBER, def);
    }
}
