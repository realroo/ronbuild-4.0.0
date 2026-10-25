package dev.autobuilder;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/** Small helper for the supply-chest animation. */
final class Compat {
    private Compat() {}

    /** Opens or closes the lid of a chest/barrel (animation + sound) if the server supports it. */
    static void lid(Block block, boolean open) {
        boolean animated = false;
        try {
            BlockState st = block.getState();
            Class<?> lidded = Class.forName("org.bukkit.block.Lidded");
            if (lidded.isInstance(st)) {
                lidded.getMethod(open ? "open" : "close").invoke(st);
                animated = true;
            }
        } catch (Throwable ignored) {
            // fall back to the sound only
        }
        if (!animated) {
            try {
                boolean barrel = block.getType().name().equals("BARREL");
                Sound s = open ? (barrel ? Sound.BLOCK_BARREL_OPEN : Sound.BLOCK_CHEST_OPEN)
                        : (barrel ? Sound.BLOCK_BARREL_CLOSE : Sound.BLOCK_CHEST_CLOSE);
                block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), s, 0.6f, 1.0f);
            } catch (Throwable ignored) {
                // no sound
            }
        }
    }
}
