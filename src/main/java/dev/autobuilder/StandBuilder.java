package dev.autobuilder;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.EulerAngle;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builder NPC for servers older than 1.21.9, which have no player-model entity:
 * an armor stand with arms, a skin head and colored armor.
 */
final class StandBuilder implements BuilderNpc {

    private final ArmorStand stand;
    private final String name;
    private boolean armUp;

    StandBuilder(Location loc, String name, String skinName, String texture, String signature) {
        this.name = name;
        this.stand = loc.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setArms(true);
            a.setBasePlate(false);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setCollidable(false);
            a.setPersistent(false);
            a.setRemoveWhenFarAway(false);
            a.addScoreboardTag(MannequinBuilder.TAG);
            a.customName(Component.text(name, NamedTextColor.GOLD));
            a.setCustomNameVisible(true);
            EntityEquipment eq = a.getEquipment();
            if (eq != null) {
                eq.setHelmet(head(skinName, texture, signature));
                eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x2E6F9E)));
                eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.fromRGB(0x3B3B6B)));
                eq.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(0x4A3320)));
            }
        });
        MannequinBuilder.LIVE.add(stand.getUniqueId());
    }

    private static ItemStack head(String skinName, String texture, String signature) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        try {
            SkullMeta meta = (SkullMeta) it.getItemMeta();
            if (texture != null && !texture.isBlank()) {
                PlayerProfile profile = Bukkit.createProfile(
                        UUID.nameUUIDFromBytes("ronautobuilder".getBytes(StandardCharsets.UTF_8)), "Builder");
                String sig = (signature == null || signature.isBlank()) ? null : signature;
                profile.setProperty(new ProfileProperty("textures", texture, sig));
                meta.setPlayerProfile(profile);
            } else if (skinName != null && !skinName.isBlank()) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(skinName));
            }
            it.setItemMeta(meta);
        } catch (Throwable ignored) {
            // plain head if the skin cannot be applied
        }
        return it;
    }

    private static ItemStack dyed(Material m, Color color) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(color);
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Entity entity() {
        return stand;
    }

    @Override
    public void moveTo(Location location) {
        stand.teleport(location);
    }

    @Override
    public void swing() {
        armUp = !armUp;
        stand.setRightArmPose(new EulerAngle(Math.toRadians(armUp ? -80 : -20), 0, 0));
    }

    @Override
    public void setHeldItem(ItemStack item) {
        EntityEquipment eq = stand.getEquipment();
        if (eq != null) eq.setItemInMainHand(item == null ? new ItemStack(Material.AIR) : item);
    }

    @Override
    public boolean isValid() {
        return stand.isValid();
    }

    @Override
    public void remove() {
        MannequinBuilder.LIVE.remove(stand.getUniqueId());
        stand.remove();
    }
}
