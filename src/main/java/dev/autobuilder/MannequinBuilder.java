package dev.autobuilder;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-model NPC using the Mannequin entity (Minecraft 1.21.9 and newer).
 * The Mannequin classes are reached by reflection so the same jar also loads on older servers,
 * where {@link StandBuilder} is used instead. NPCs are never saved with the world.
 */
final class MannequinBuilder implements BuilderNpc {

    /** Scoreboard tag put on every NPC entity we spawn, used to clean up orphans. */
    static final String TAG = "ronautobuilder_npc";

    /** Entities that are currently managed by this plugin. */
    static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();

    private static Class<?> mannequinClass;
    private static boolean probed;

    static boolean available() {
        if (!probed) {
            probed = true;
            try {
                mannequinClass = Class.forName("org.bukkit.entity.Mannequin");
            } catch (Throwable t) {
                mannequinClass = null;
            }
        }
        return mannequinClass != null;
    }

    private final LivingEntity entity;
    private final String name;

    @SuppressWarnings("unchecked")
    MannequinBuilder(Location loc, String name, String skinName, String texture, String signature) {
        if (!available()) throw new UnsupportedOperationException("Mannequin entities are not available");
        this.name = name;
        Class<LivingEntity> type = (Class<LivingEntity>) mannequinClass;
        this.entity = loc.getWorld().spawn(loc, type, m -> configure(m, name, skinName, texture, signature));
        LIVE.add(entity.getUniqueId());
    }

    private static void configure(LivingEntity m, String name, String skinName, String texture, String signature) {
        m.setGravity(false);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.setCollidable(false);
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.addScoreboardTag(TAG);
        m.customName(Component.text(name, NamedTextColor.GOLD));
        m.setCustomNameVisible(true);
        try {
            mannequinClass.getMethod("setImmovable", boolean.class).invoke(m, true);
        } catch (Throwable ignored) {
            // still works, it can just be pushed
        }
        try {
            mannequinClass.getMethod("setDescription", Component.class).invoke(m, new Object[]{null});
        } catch (Throwable ignored) {
            // keep the default description
        }
        applySkin(m, skinName, texture, signature);
    }

    private static void applySkin(LivingEntity m, String skinName, String texture, String signature) {
        boolean hasTexture = texture != null && !texture.isBlank();
        boolean hasName = skinName != null && !skinName.isBlank();
        if (!hasTexture && !hasName) return;
        try {
            Class<?> profileClass = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            Class<?> builderClass = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile$Builder");
            Object builder = profileClass.getMethod("resolvableProfile").invoke(null);
            if (hasTexture) {
                builderClass.getMethod("name", String.class).invoke(builder, "Builder");
                builderClass.getMethod("uuid", UUID.class)
                        .invoke(builder, UUID.nameUUIDFromBytes("ronautobuilder".getBytes(StandardCharsets.UTF_8)));
                String sig = (signature == null || signature.isBlank()) ? null : signature;
                builderClass.getMethod("addProperty", ProfileProperty.class)
                        .invoke(builder, new ProfileProperty("textures", texture, sig));
            } else {
                builderClass.getMethod("name", String.class).invoke(builder, skinName);
            }
            Object profile = builderClass.getMethod("build").invoke(builder);
            mannequinClass.getMethod("setProfile", profileClass).invoke(m, profile);
        } catch (Throwable ignored) {
            // keep the default skin if the profile is rejected
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Entity entity() {
        return entity;
    }

    @Override
    public void moveTo(Location location) {
        entity.teleport(location);
    }

    @Override
    public void swing() {
        entity.swingMainHand();
    }

    @Override
    public void setHeldItem(ItemStack item) {
        EntityEquipment eq = entity.getEquipment();
        if (eq != null) eq.setItemInMainHand(item == null ? new ItemStack(Material.AIR) : item);
    }

    @Override
    public boolean isValid() {
        return entity.isValid();
    }

    @Override
    public void remove() {
        LIVE.remove(entity.getUniqueId());
        entity.remove();
    }
}
