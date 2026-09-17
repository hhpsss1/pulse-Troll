package cc.aerial.client.screen.tabs.scripts;

import java.util.List;

/** Curated quick-reference for the scripting API, shown in the Scripts tab's Docs drawer. */
public final class ScriptDocs {
    private ScriptDocs() {
    }

    public record Entry(String signature, String description) {
    }

    public record Section(String title, List<Entry> entries) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("events", List.of(
                    new Entry("events.onTick(fn)", "Every game tick."),
                    new Entry("events.onRender2D(fn)", "Draw HUD: fn(r) gets a 2D surface."),
                    new Entry("events.onRender3D(fn)", "Draw in world: fn(r) gets a 3D surface (ESP)."),
                    new Entry("events.onChat(fn)", "fn(e): e.getMessage(), e.cancel()."),
                    new Entry("events.onKey(fn)", "fn(e): e.getKey(), e.isPressed(), e.cancel()."),
                    new Entry("events.onSendPacket(fn)", "fn(e): e.getName(), e.cancel()."),
                    new Entry("events.onReceivePacket(fn)", "fn(e): e.getName(), e.cancel()."),
                    new Entry("events.onAttack(fn)", "fn(entity) when you attack."),
                    new Entry("events.onJump(fn) / onWorldJoin(fn)", "Jump / world join.")
            )),
            new Section("render2d (r)", List.of(
                    new Entry("r.rect(x,y,w,h,color)", "Filled rectangle."),
                    new Entry("r.roundedRect(x,y,w,h,radius,color)", "Rounded rectangle."),
                    new Entry("r.outline(x,y,w,h,radius,thick,color)", "Rounded outline."),
                    new Entry("r.gradient(x,y,w,h,c1,c2,vertical)", "Two-colour gradient."),
                    new Entry("r.line(x1,y1,x2,y2,thick,color)", "Line."),
                    new Entry("r.text(s,x,y,size,color)", "Text (also textBold / textShadow)."),
                    new Entry("r.textWidth(s,size) / r.width() / r.height()", "Measure / screen size.")
            )),
            new Section("render3d (r)", List.of(
                    new Entry("r.box(x1,y1,z1,x2,y2,z2,color,thruWalls)", "Box outline in world."),
                    new Entry("r.boxFilled(...)", "Filled box."),
                    new Entry("r.entityBox(entity,color,filled,thruWalls)", "Box around an entity (ESP)."),
                    new Entry("r.line(x1,y1,z1,x2,y2,z2,color,w,thru)", "World line."),
                    new Entry("r.circle(x,y,z,radius,color,thru)", "Horizontal circle."),
                    new Entry("r.tracer(x,y,z,color,w)", "Line from camera to a point."),
                    new Entry("r.worldToScreen(x,y,z)", "[screenX, screenY, onScreen].")
            )),
            new Section("game", List.of(
                    new Entry("game.getPlayer() / getWorld()", "Local player / world (or null)."),
                    new Entry("game.getEntities() / getPlayers()", "Entity/player list (ScriptEntity)."),
                    new Entry("game.attackEntity(entity) / useItem()", "Attack / use held item."),
                    new Entry("game.setKey(name, pressed)", "Hold a key: forward, jump, sneak, attack…"),
                    new Entry("game.isInWorld() / isInGui()", "State checks."),
                    new Entry("game.sendChatMessage(text)", "Send chat / run a command."),
                    new Entry("game.addChatMessage(text)", "Client-only chat message."),
                    new Entry("game.getFPS() / getServerName()", "FPS / current server ip.")
            )),
            new Section("player", List.of(
                    new Entry("getX/Y/Z(), getYaw/getPitch()", "Position and rotation."),
                    new Entry("getHealth/getMaxHealth/getFoodLevel()", "Vitals."),
                    new Entry("getMotionX/Y/Z(), setVelocity(x,y,z)", "Read / set velocity."),
                    new Entry("addVelocity(x,y,z) / jump()", "Nudge velocity / jump."),
                    new Entry("isSprinting/setSprinting(b)", "Sprint (also sneaking)."),
                    new Entry("setPosition(x,y,z) / setRotation(y,p)", "Teleport / aim."),
                    new Entry("swingHand() / getHeldItemName()", "Swing / held item name."),
                    new Entry("distanceTo(x,y,z) / isOnGround()", "Distance / grounded.")
            )),
            new Section("entity", List.of(
                    new Entry("getName() / getType() / getId()", "Identity."),
                    new Entry("getX/Y/Z(), getEyeY()", "Position."),
                    new Entry("getHealth() / getMaxHealth()", "Vitals (0 if not living)."),
                    new Entry("isPlayer() / isLiving() / isAlive()", "Type checks."),
                    new Entry("distanceToPlayer()", "Distance to you."),
                    new Entry("getMin() / getMax()", "Bounding box corners [x,y,z].")
            )),
            new Section("world", List.of(
                    new Entry("getDimensionName()", "Current dimension."),
                    new Entry("getBlockName(x, y, z) / isBlockSolid(...)", "Block queries."),
                    new Entry("getTime() / isDaytime()", "World time / day."),
                    new Entry("getPlayers() / getPlayerCount()", "Player list.")
            )),
            new Section("modules", List.of(
                    new Entry("modules.registerModule(name, desc, category)", "Create a toggleable module."),
                    new Entry("module.setOnEnable/Disable/onTick(fn)", "Lifecycle + tick hooks."),
                    new Entry("category", "COMBAT, MOVEMENT, VISUAL, WORLD, UTILITY, SCRIPTS.")
            )),
            new Section("hud", List.of(
                    new Entry("hud.registerHUDElement(name)", "Create a HUD element."),
                    new Entry("element.setPosition(x, y) / setVisible(b)", "Place / show."),
                    new Entry("element.setRenderCallback(fn)", "Draw each frame.")
            )),
            new Section("utils", List.of(
                    new Entry("utils.color(r,g,b,a) / colorRGB(r,g,b)", "Make an ARGB colour."),
                    new Entry("utils.rainbow(speed,offset) / hsb(h,s,b)", "Animated / HSB colour."),
                    new Entry("utils.withAlpha(color, a)", "Change a colour's alpha."),
                    new Entry("utils.lerp(a,b,t) / clamp(v,min,max)", "Math helpers."),
                    new Entry("utils.distance2D/3D(...)", "Distance helpers."),
                    new Entry("utils.log(text) / logError(text)", "Console output."),
                    new Entry("utils.randomInt/randomDouble/sleep/formatTime", "Misc utilities.")
            ))
    );
}
