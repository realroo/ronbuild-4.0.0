package dev.autobuilder;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Standalone player-model NPCs (admin-created, saved in npcs.yml) that run commands when clicked.
 * Command specs are stored as "player:warp spawn", "console:tp {player} 0 100 0" or "say:Welcome!".
 */
final class NpcManager implements Listener {

    private static final class Data {
        final String name;
        Location loc;
        String skin = "";
        BuilderNpc live;
        int missing;
        final List<String> commands = new ArrayList<>();

        Data(String name, Location loc) {
            this.name = name;
            this.loc = loc;
        }
    }

    private final AutoBuilderPlugin plugin;
    private final Map<String, Data> npcs = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private BukkitTask watchdog;
    private boolean loaded;

    NpcManager(AutoBuilderPlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "npcs.yml");
    }

    private Data find(String name) {
        return name == null ? null : npcs.get(name.toLowerCase(Locale.ROOT));
    }

    private static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_]{1,16}");
    }

    void load() {
        File f = file();
        if (f.isFile()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection sec = yml.getConfigurationSection("npcs");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection n = sec.getConfigurationSection(key);
                    if (n == null) continue;
                    World w = Bukkit.getWorld(n.getString("world", ""));
                    if (w == null) continue;
                    Location loc = new Location(w, n.getDouble("x"), n.getDouble("y"), n.getDouble("z"),
                            (float) n.getDouble("yaw"), (float) n.getDouble("pitch"));
                    Data d = new Data(n.getString("name", key), loc);
                    d.commands.addAll(n.getStringList("commands"));
                    d.skin = n.getString("skin", "");
                    npcs.put(key.toLowerCase(Locale.ROOT), d);
                    spawn(d);
                }
            }
        }
        loaded = true;
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, this::watch, 100L, 100L);
    }

    void save() {
        if (!loaded) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Data d : npcs.values()) {
            String base = "npcs." + d.name;
            yml.set(base + ".name", d.name);
            yml.set(base + ".world", d.loc.getWorld().getName());
            yml.set(base + ".x", d.loc.getX());
            yml.set(base + ".y", d.loc.getY());
            yml.set(base + ".z", d.loc.getZ());
            yml.set(base + ".yaw", (double) d.loc.getYaw());
            yml.set(base + ".pitch", (double) d.loc.getPitch());
            yml.set(base + ".skin", d.skin);
            yml.set(base + ".commands", d.commands);
        }
        try {
            yml.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save npcs.yml: " + ex);
        }
    }

    /** Despawns every NPC cleanly (they are respawned from npcs.yml on the next start). */
    void shutdown() {
        if (watchdog != null) watchdog.cancel();
        save();
        for (Data d : npcs.values()) {
            if (d.live != null) d.live.remove();
        }
        npcs.clear();
    }

    private void spawn(Data d) {
        try {
            d.live = plugin.spawnBuilder(d.loc, d.name, d.skin);
            d.missing = 0;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not spawn NPC " + d.name + ": " + t);
        }
    }

    /** Respawns NPCs that vanished (for example when their chunk was unloaded). */
    private void watch() {
        for (Data d : npcs.values()) {
            if (d.live != null && d.live.isValid()) {
                d.missing = 0;
                continue;
            }
            World w = d.loc.getWorld();
            if (w == null || !w.isChunkLoaded(d.loc.getBlockX() >> 4, d.loc.getBlockZ() >> 4)) continue;
            if (++d.missing >= 2) {
                if (d.live != null) d.live.remove();
                spawn(d);
            }
        }
    }

    boolean create(Player p, String name) {
        if (!validName(name)) {
            Msg.error(p, "NPC names can have 1-16 letters, numbers or _ .");
            return false;
        }
        if (find(name) != null) {
            Msg.error(p, "An NPC with that name already exists.");
            return false;
        }
        if (npcs.size() >= plugin.getConfig().getInt("npc.max-npcs", 50)) {
            Msg.error(p, "NPC limit reached (npc.max-npcs in config.yml).");
            return false;
        }
        Data d = new Data(name, p.getLocation().clone());
        npcs.put(name.toLowerCase(Locale.ROOT), d);
        spawn(d);
        save();
        Msg.info(p, "Created NPC " + name + ". Example: /ab npc command add " + name + " player warp spawn");
        return true;
    }

    boolean remove(Player p, String name) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        if (d.live != null) d.live.remove();
        npcs.remove(name.toLowerCase(Locale.ROOT));
        save();
        Msg.info(p, "Removed NPC " + d.name + ".");
        return true;
    }

    List<String> names() {
        List<String> out = new ArrayList<>();
        for (Data d : npcs.values()) out.add(d.name);
        return out;
    }

    boolean moveHere(Player p, String name) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        d.loc = p.getLocation().clone();
        if (d.live != null) d.live.moveTo(d.loc);
        save();
        Msg.info(p, "Moved " + d.name + " to you.");
        return true;
    }

    boolean tpTo(Player p, String name) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        p.teleport(d.loc);
        return true;
    }

    /** Sets the skin of one NPC to a Minecraft username, or resets it to the default builder skin. */
    boolean setSkin(Player p, String name, String skin) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        boolean reset = skin.equalsIgnoreCase("reset");
        if (!reset && !skin.matches("[A-Za-z0-9_]{1,16}")) {
            Msg.error(p, "That is not a valid Minecraft username.");
            return false;
        }
        d.skin = reset ? "" : skin;
        if (d.live != null) d.live.remove();
        spawn(d);
        save();
        Msg.info(p, reset ? d.name + " uses the default builder skin." : d.name + " now uses the skin of " + skin + ".");
        return true;
    }

    boolean addCommand(Player p, String name, String spec) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        d.commands.add(spec);
        save();
        Msg.info(p, "Added. " + d.name + " now has " + d.commands.size() + " click action(s). {player} is replaced by the clicker.");
        return true;
    }

    boolean clearCommands(Player p, String name) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return false;
        }
        d.commands.clear();
        save();
        Msg.info(p, "Cleared click actions of " + d.name + ".");
        return true;
    }

    void sendCommands(Player p, String name) {
        Data d = find(name);
        if (d == null) {
            Msg.error(p, "No NPC named " + name + ".");
            return;
        }
        if (d.commands.isEmpty()) {
            Msg.info(p, d.name + " has no click actions.");
            return;
        }
        for (int i = 0; i < d.commands.size(); i++) Msg.info(p, (i + 1) + ". " + d.commands.get(i));
    }

    private Data byEntity(Entity e) {
        for (Data d : npcs.values()) {
            if (d.live == null) continue;
            Entity le = d.live.entity();
            if (le != null && le.getUniqueId().equals(e.getUniqueId())) return d;
        }
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Data d = byEntity(e.getRightClicked());
        if (d == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        cooldown.put(p.getUniqueId(), now);
        for (String spec : d.commands) run(p, spec);
    }

    /** Removes leftover NPC entities (for example after a crash) that this plugin does not manage. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en.getScoreboardTags().contains(MannequinBuilder.TAG)
                    && !MannequinBuilder.LIVE.contains(en.getUniqueId())) {
                en.remove();
            }
        }
    }

    private void run(Player p, String spec) {
        int i = spec.indexOf(':');
        if (i < 0) return;
        String type = spec.substring(0, i);
        String cmd = spec.substring(i + 1).replace("{player}", p.getName());
        switch (type) {
            case "player" -> p.performCommand(cmd);
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            case "say" -> p.sendMessage(Component.text(cmd));
            default -> { }
        }
    }
}
