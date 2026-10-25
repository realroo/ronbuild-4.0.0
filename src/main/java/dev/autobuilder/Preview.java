package dev.autobuilder;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Build preview: a particle outline of the footprint, or "ghost blocks" shown only to the player. */
final class Preview implements Listener {

    enum Type { OUTLINE, GHOST }

    private static final class Ghost {
        Blueprint bp;
        World world;
        int ox, oy, oz;
        int[] idx;
        int sent;
    }

    private final AutoBuilderPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Ghost> ghosts = new HashMap<>();

    Preview(AutoBuilderPlugin plugin) {
        this.plugin = plugin;
    }

    void show(Player p, Session s) {
        if (!plugin.ready(p, s)) return;
        clear(p);
        Blueprint rb = s.blueprint.rotated(s.rotation);
        if (s.preview == Type.GHOST) ghost(p, s, rb);
        else outline(p, s, rb);
    }

    /** Redraws an active preview (used after rotating or moving the origin). */
    void refresh(Player p, Session s) {
        UUID id = p.getUniqueId();
        if ((tasks.containsKey(id) || ghosts.containsKey(id)) && s.blueprint != null && s.origin != null) {
            show(p, s);
        }
    }

    void clear(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
        Ghost g = ghosts.remove(id);
        if (g != null) restore(p, g);
    }

    void clearAll() {
        for (BukkitTask t : tasks.values()) t.cancel();
        tasks.clear();
        for (Map.Entry<UUID, Ghost> e : ghosts.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) restore(p, e.getValue());
        }
        ghosts.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
        ghosts.remove(id);
    }

    private void outline(Player p, Session s, Blueprint rb) {
        Particle dust = Particle.DUST;
        World w = s.origin.getWorld();
        double x1 = s.origin.getBlockX(), y1 = s.origin.getBlockY(), z1 = s.origin.getBlockZ();
        double x2 = x1 + rb.width(), y2 = y1 + rb.height(), z2 = z1 + rb.length();
        Particle.DustOptions opts = new Particle.DustOptions(Color.AQUA, 1.2f);
        int seconds = plugin.getConfig().getInt("preview.outline-seconds", 15);
        UUID id = p.getUniqueId();

        BukkitTask t = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks > seconds * 20 || !p.isOnline() || !p.getWorld().equals(w)) {
                    cancel();
                    tasks.remove(id);
                    return;
                }
                for (double y : new double[]{y1, y2}) for (double z : new double[]{z1, z2}) line(p, dust, opts, x1, y, z, x2, y, z);
                for (double x : new double[]{x1, x2}) for (double z : new double[]{z1, z2}) line(p, dust, opts, x, y1, z, x, y2, z);
                for (double x : new double[]{x1, x2}) for (double y : new double[]{y1, y2}) line(p, dust, opts, x, y, z1, x, y, z2);
                ticks += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        tasks.put(id, t);
        Msg.info(p, "Outline preview for " + seconds + " seconds (/ab preview off to hide).");
    }

    private static void line(Player p, Particle particle, Particle.DustOptions d,
                             double ax, double ay, double az, double bx, double by, double bz) {
        double len = Math.max(Math.max(Math.abs(bx - ax), Math.abs(by - ay)), Math.abs(bz - az));
        int steps = Math.min(Math.max(1, (int) (len / 0.5)), 80);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            p.spawnParticle(particle, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 1, 0, 0, 0, 0, d);
        }
    }

    private void ghost(Player p, Session s, Blueprint rb) {
        int max = plugin.getConfig().getInt("preview.max-ghost-blocks", 20000);
        int seconds = plugin.getConfig().getInt("preview.ghost-seconds", 30);
        int[] order = BuildOrder.create(rb, BuildJob.Pattern.UP, 0, 0, true, false);
        Ghost g = new Ghost();
        g.bp = rb;
        g.world = s.origin.getWorld();
        g.ox = s.origin.getBlockX();
        g.oy = s.origin.getBlockY();
        g.oz = s.origin.getBlockZ();
        g.idx = Arrays.copyOf(order, Math.min(order.length, max));
        UUID id = p.getUniqueId();

        BukkitTask t = new BukkitRunnable() {
            int waited = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    tasks.remove(id);
                    ghosts.remove(id);
                    return;
                }
                int end = Math.min(g.idx.length, g.sent + 1500);
                for (; g.sent < end; g.sent++) {
                    int idx = g.idx[g.sent];
                    p.sendBlockChange(loc(g, idx), g.bp.data(idx));
                }
                if (g.sent >= g.idx.length && ++waited > seconds * 20) {
                    cancel();
                    tasks.remove(id);
                    Ghost done = ghosts.remove(id);
                    if (done != null) restore(p, done);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        tasks.put(id, t);
        ghosts.put(id, g);
        Msg.info(p, "Ghost preview: " + g.idx.length + " blocks"
                + (order.length > g.idx.length ? " (first " + max + " of " + order.length + ", bottom layers)" : "")
                + " for " + seconds + " seconds. Only you can see them. /ab preview off to hide.");
    }

    private static Location loc(Ghost g, int idx) {
        int w = g.bp.width(), l = g.bp.length();
        int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
        return new Location(g.world, g.ox + x, g.oy + y, g.oz + z);
    }

    private static void restore(Player p, Ghost g) {
        for (int i = 0; i < g.sent; i++) {
            Location loc = loc(g, g.idx[i]);
            p.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }
}
