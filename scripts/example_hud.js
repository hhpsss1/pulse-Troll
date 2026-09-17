// Example: Creating a custom HUD element via JavaScript
// This script creates a simple FPS and coordinates display

// Register a new HUD element
const infoHUD = hud.registerHUDElement("Info Display");

// Set up the HUD element
infoHUD.setPosition(10, 10);
infoHUD.setVisible(true);

// Set the render callback
infoHUD.setRenderCallback(function() {
    const player = game.getPlayer();
    const world = game.getWorld();
    
    if (player && world) {
        const fps = game.getFPS();
        const x = Math.floor(player.getX());
        const y = Math.floor(player.getY());
        const z = Math.floor(player.getZ());
        const dimension = world.getDimensionName();
        
        // Log the info (in a real implementation, this would render text on screen)
        utils.log("HUD: FPS=" + fps + " Pos=[" + x + ", " + y + ", " + z + "] Dim=" + dimension);
    }
});

utils.log("Info HUD script loaded successfully!");
