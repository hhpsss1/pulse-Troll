package cc.aerial.client.screen.tabs.scripts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** The state of one visual-constructor project: a single trigger plus condition and action blocks. */
public final class ScriptModel {
    public String name = "MyScript";
    public String description = "Made with the Aerial script constructor";
    public BlockDef trigger = BlockPalette.TRIGGERS.get(0);
    public final List<Block> conditions = new ArrayList<>();
    public final List<Block> actions = new ArrayList<>();

    /** A placed block: its definition plus the current value of each parameter. */
    public static final class Block {
        public final BlockDef def;
        public final String[] values;

        public Block(BlockDef def) {
            this.def = def;
            this.values = new String[def.params.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = def.params.get(i).defaultValue();
            }
        }
    }

    public Block addCondition(BlockDef def) {
        Block block = new Block(def);
        conditions.add(block);
        return block;
    }

    public Block addAction(BlockDef def) {
        Block block = new Block(def);
        actions.add(block);
        return block;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("description", description);
        root.addProperty("trigger", trigger.id);
        root.add("conditions", blocksToJson(conditions));
        root.add("actions", blocksToJson(actions));
        return root;
    }

    private static JsonArray blocksToJson(List<Block> blocks) {
        JsonArray array = new JsonArray();
        for (Block block : blocks) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", block.def.id);
            JsonArray values = new JsonArray();
            for (String value : block.values) {
                values.add(value);
            }
            obj.add("values", values);
            array.add(obj);
        }
        return array;
    }

    public static ScriptModel fromJson(JsonObject root) {
        ScriptModel model = new ScriptModel();
        if (root.has("name")) {
            model.name = root.get("name").getAsString();
        }
        if (root.has("description")) {
            model.description = root.get("description").getAsString();
        }
        if (root.has("trigger")) {
            BlockDef trigger = BlockPalette.byId(root.get("trigger").getAsString());
            if (trigger != null) {
                model.trigger = trigger;
            }
        }
        readBlocks(root, "conditions", model.conditions);
        readBlocks(root, "actions", model.actions);
        return model;
    }

    private static void readBlocks(JsonObject root, String key, List<Block> into) {
        if (!root.has(key)) {
            return;
        }
        for (var element : root.getAsJsonArray(key)) {
            JsonObject obj = element.getAsJsonObject();
            BlockDef def = BlockPalette.byId(obj.get("id").getAsString());
            if (def == null) {
                continue;
            }
            Block block = new Block(def);
            if (obj.has("values")) {
                JsonArray values = obj.getAsJsonArray("values");
                for (int i = 0; i < block.values.length && i < values.size(); i++) {
                    block.values[i] = values.get(i).getAsString();
                }
            }
            into.add(block);
        }
    }
}
