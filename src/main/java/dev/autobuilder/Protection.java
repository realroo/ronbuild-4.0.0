package dev.autobuilder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Checks land protection by firing the same events a real player action would fire,
 * so any claim or region plugin can veto a placement or a container access.
 */
final class Protection {
    private Protection() {}

    static boolean canBuild(Player p, Block block) {
        try {
            BlockState replaced = block.getState();
            Block against = block.getRelative(BlockFace.DOWN);
            BlockPlaceEvent ev = new BlockPlaceEvent(block, replaced, against, new ItemStack(Material.STONE),
                    p, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(ev);
            return !ev.isCancelled() && ev.canBuild();
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean canOpen(Player p, Block block) {
        try {
            PlayerInteractEvent ev = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP);
            Bukkit.getPluginManager().callEvent(ev);
            return ev.useInteractedBlock() != Event.Result.DENY;
        } catch (Throwable t) {
            return true;
        }
    }
}
