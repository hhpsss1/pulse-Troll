package cc.aerial.client.screen.tabs.scripts;

/**
 * Builds a single self-contained, machine-readable reference + system prompt for the Aerial
 * scripting API. Hand this to an LLM (ChatGPT/Claude/etc.) and it can write working scripts.
 * Generated from {@link ScriptDocs} so it never drifts from the in-app documentation.
 */
public final class ScriptApiReference {
    private ScriptApiReference() {
    }

    public static String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Aerial Client — JavaScript scripting API (for AI code generation)\n\n");
        sb.append("You write scripts for the Aerial Minecraft client. The runtime is **Nashorn ")
                .append("JavaScript (~ES5)**. Follow these rules exactly.\n\n");

        sb.append("## Rules\n");
        sb.append("- Use `var` and `function () { }`. Do NOT use `let`, `const`, arrow functions, ")
                .append("classes, template strings, destructuring, or `import`/`require`.\n");
        sb.append("- Only the globals listed below exist. There is no DOM, no Node, no fetch.\n");
        sb.append("- Concatenate strings with `+`. Use `Math.floor`, `Math.abs`, etc. freely.\n");
        sb.append("- To run logic continuously, either register a module and use `setOnTick`, ")
                .append("or call `events.onTick(fn)`.\n");
        sb.append("- To draw a HUD, use `events.onRender2D(fn)` or a `hud` element. ")
                .append("To draw in the world (ESP), use `events.onRender3D(fn)`.\n");
        sb.append("- Colors are ARGB integers: build them with `utils.color(r, g, b, a)` (0-255 each).\n");
        sb.append("- 2D coords are screen pixels (origin top-left). 3D coords are world coords.\n");
        sb.append("- Cancelable events (chat/key/packets) expose `e.cancel()`.\n");
        sb.append("- Output ONLY the script code unless asked otherwise.\n\n");

        sb.append("## Globals and methods\n");
        for (ScriptDocs.Section section : ScriptDocs.SECTIONS) {
            sb.append("\n### ").append(section.title()).append("\n");
            for (ScriptDocs.Entry entry : section.entries()) {
                sb.append("- `").append(entry.signature()).append("` — ")
                        .append(entry.description()).append("\n");
            }
        }

        sb.append("\n## Module categories\n");
        sb.append("COMBAT, MOVEMENT, VISUAL, WORLD, UTILITY, SCRIPTS. Modules you create appear under ")
                .append("the Scripts category and start disabled — the user toggles them.\n");

        sb.append("\n## Complete examples\n\n");
        sb.append("### Toggleable module (runs while enabled)\n");
        sb.append("```js\n");
        sb.append("var m = modules.registerModule(\"Auto Sprint\", \"Sprint while on ground\", \"MOVEMENT\");\n");
        sb.append("m.setOnTick(function () {\n");
        sb.append("    var p = game.getPlayer();\n");
        sb.append("    if (p && p.isOnGround()) p.setSprinting(true);\n");
        sb.append("});\n");
        sb.append("```\n\n");

        sb.append("### Custom draggable HUD element\n");
        sb.append("```js\n");
        sb.append("var e = hud.registerHUDElement(\"Coords\");\n");
        sb.append("e.setSize(96, 22);\n");
        sb.append("e.setBackground(utils.color(0, 0, 0, 150));\n");
        sb.append("e.setRenderCallback(function (r) {\n");
        sb.append("    var p = game.getPlayer();\n");
        sb.append("    if (!p) return;\n");
        sb.append("    r.text(\"XYZ \" + Math.floor(p.getX()) + \" \" + Math.floor(p.getY()) + \" \" + Math.floor(p.getZ()),\n");
        sb.append("           6, 8, 8, utils.color(255, 255, 255, 255));\n");
        sb.append("});\n");
        sb.append("```\n\n");

        sb.append("### Player ESP (world boxes + tracers)\n");
        sb.append("```js\n");
        sb.append("events.onRender3D(function (r) {\n");
        sb.append("    var list = game.getPlayers();\n");
        sb.append("    for (var i = 0; i < list.length; i++) {\n");
        sb.append("        var color = utils.color(255, 70, 70, 170);\n");
        sb.append("        r.entityBox(list[i], color, false, true);\n");
        sb.append("        r.tracer(list[i].getX(), list[i].getEyeY(), list[i].getZ(), color, 1.0);\n");
        sb.append("    }\n");
        sb.append("});\n");
        sb.append("```\n\n");

        sb.append("### React to chat\n");
        sb.append("```js\n");
        sb.append("events.onChat(function (e) {\n");
        sb.append("    if (e.getMessage().indexOf(\"gg\") >= 0) game.sendChatMessage(\"gg\");\n");
        sb.append("});\n");
        sb.append("```\n\n");

        sb.append("## Your task\n");
        sb.append("Write one Aerial JavaScript script that does the following:\n");
        sb.append("<describe what the script should do here>\n");
        return sb.toString();
    }
}
