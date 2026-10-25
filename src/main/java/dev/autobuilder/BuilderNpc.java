package dev.autobuilder;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** A visible builder NPC (a player-model Mannequin). */
interface BuilderNpc {
    String name();

    /** The underlying entity, or null if it is gone. */
    Entity entity();

    void moveTo(Location location);

    void swing();

    /** Item in the main hand; null means empty hand. */
    void setHeldItem(ItemStack item);

    boolean isValid();

    void remove();
}
