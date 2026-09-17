// Example: Creating a custom module via JavaScript
// This script registers a simple "AutoJump" module that automatically jumps when on ground

// Register a new module
const autoJumpModule = modules.registerModule(
    "AutoJump",
    "Automatically jumps when on ground",
    "MOVEMENT"
);

// Set up the module behavior
autoJumpModule.setOnEnable(function() {
    utils.log("AutoJump module enabled!");
});

autoJumpModule.setOnDisable(function() {
    utils.log("AutoJump module disabled!");
});

autoJumpModule.setOnTick(function() {
    const player = game.getPlayer();
    if (player && player.isOnGround()) {
        // Jump by setting vertical velocity
        // Note: This is a simplified example - actual implementation would need proper velocity handling
        utils.log("AutoJump: Player is on ground, should jump");
    }
});

// Enable the module by default (optional)
// autoJumpModule.setEnabled(true);

utils.log("AutoJump module script loaded successfully!");
