package dev.autobuilder;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One running build: one or more NPC builders placing blocks one by one. */
public final class BuildJob {

    public enum State { RUNNING, PAUSED, DONE, CANCELLED }

    public enum Pattern { UP, DOWN, RANDOM, OUTWARD, LEFT, RIGHT, NORTH, SOUTH, EAST, WEST }

    /** SHARED: builders share one queue. OPPOSITE: odd builders start at the other end. SPLIT: own section each. */
    public enum Mode { SHARED, OPPOSITE, SPLIT }

    /** CONTRACT: the builders bring the blocks and the player pays the shop price. */
    public enum Supply { FREE, INVENTORY, CHEST, CONTRACT }

    public record Options(boolean ignoreAir, boolean ignoreWater, Supply supply, int builders, double speed,
                          Pattern pattern, Mode mode, int sweepX, int sweepZ, boolean survival, double labor) {}

    /** What a build costs: paid before the first block (upfront) and while building (payAsYouGo). */
    public record Quote(int blocks, double hire, double labor, double materials, double pool,
                        double upfront, double payAsYouGo) {}

    static final class Range {
        int lo, hi; // hi is exclusive

        Range(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private static final class Worker {
        BuilderNpc npc;
        Range range;
        boolean fromBack;
        int current = -1;
        double credit;
        int trip;
        final Map<Material, Integer> carried = new EnumMap<>(Material.class);
    }

    private enum PayResult { OK, TRIP, MISSING }

    private record Change(int x, int y, int z, BlockData previous) {}

    private static final Set<Material> TALL = EnumSet.of(Material.TALL_GRASS, Material.LARGE_FERN,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.TALL_SEAGRASS);

    private final AutoBuilderPlugin plugin;
    private final UUID ownerId;
    private final World world;
    private final int ox, oy, oz;
    private final Blueprint source, bp;
    private final int rotation;
    private final Options opts;
    private final Location chest;
    private final int[] order;
    private final int maxUndo;
    private final int batchBlocks;
    private final List<Range> ranges = new ArrayList<>();
    private final List<Worker> workers = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();

    private BukkitTask task;
    private BossBar bar;
    private State state = State.RUNNING;
    private int processed, placed, denied, tickCount;
    private boolean undoTruncated;
    private long lastWarn;
    private double pool; // money already paid but not yet used up

    public BuildJob(AutoBuilderPlugin plugin, UUID ownerId, Location origin, Blueprint source, int rotation,
                    Options opts, Location chest) {
        this.plugin = plugin;
        this.ownerId = ownerId;
        this.world = origin.getWorld();
        this.ox = origin.getBlockX();
        this.oy = origin.getBlockY();
        this.oz = origin.getBlockZ();
        this.source = source;
        this.rotation = rotation;
        this.bp = source.rotated(rotation);
        this.opts = opts;
        this.chest = chest;
        this.maxUndo = plugin.getConfig().getInt("build.max-undo", 300000);
        this.batchBlocks = Math.max(1, plugin.eco().getInt("charge-batch-blocks", 10));
        this.order = BuildOrder.create(bp, opts.pattern(), opts.sweepX(), opts.sweepZ(), opts.ignoreAir(),
                plugin.getConfig().getBoolean("build.smart-order", true));
    }

    // ------------------------------------------------------------------ info

    public Blueprint source() { return source; }
    public int rotation() { return rotation; }
    public Location origin() { return new Location(world, ox, oy, oz); }
    public Location chestLocation() { return chest; }
    public State state() { return state; }
    public int processed() { return processed; }
    public int placed() { return placed; }
    public int total() { return order.length; }
    public boolean isActive() { return state == State.RUNNING || state == State.PAUSED; }

    public List<BuilderNpc> builders() {
        List<BuilderNpc> out = new ArrayList<>();
        for (Worker w : workers) {
            if (w.npc != null && w.npc.isValid()) out.add(w.npc);
        }
        return out;
    }

    // ------------------------------------------------------------------ price

    /** Prices this build: only blocks that still have to be placed are counted. */
    Quote quote(double hire) {
        boolean contract = opts.supply() == Supply.CONTRACT;
        int toPlace = 0;
        double materials = 0;
        for (int idx : order) {
            BlockData target = bp.data(idx);
            if (skip(blockOf(idx), target)) continue;
            toPlace++;
            if (contract) {
                Material m = costOf(target);
                if (m != null) materials += plugin.shop().price(m) * amountOf(target);
            }
        }
        double labor = toPlace * opts.labor();
        double prepaid = contract ? materials + labor : 0;
        double payAsYouGo = contract ? 0 : labor;
        Wallet wallet = plugin.wallet();
        if (wallet != null) {
            hire = wallet.round(hire);
            prepaid = wallet.round(prepaid);
            payAsYouGo = wallet.round(payAsYouGo);
        }
        return new Quote(toPlace, hire, labor, materials, prepaid, hire + prepaid, payAsYouGo);
    }

    /** Adds money that was already charged for this build. */
    void prepay(double amount) {
        pool += amount;
    }

    private double blockCost(BlockData target) {
        double cost = opts.labor();
        if (opts.supply() == Supply.CONTRACT) {
            Material m = costOf(target);
            if (m != null) cost += plugin.shop().price(m) * amountOf(target);
        }
        return cost;
    }

    /** Makes sure the next block is paid for; pauses the build when the player cannot afford it. */
    private boolean ensureFunds(Player owner, double cost) {
        if (cost <= 0 || pool + 1e-9 >= cost) return true;
        Wallet wallet = plugin.wallet();
        if (wallet == null || owner == null) return true;
        double batch = wallet.round(Math.max(cost - pool, cost * batchBlocks));
        if (wallet.withdraw(owner, batch)) {
            pool += batch;
            return true;
        }
        double exact = wallet.round(cost - pool);
        if (exact > 0 && exact < batch && wallet.withdraw(owner, exact)) {
            pool += exact;
            return true;
        }
        state = State.PAUSED;
        Msg.error(owner, "Build paused: Insufficient funds! Deposit more " + wallet.currencyName()
                + " and run /ab resume");
        return false;
    }

    /** Gives back the money that was paid but not used. */
    private void refundPool() {
        if (pool <= 0) return;
        Wallet wallet = plugin.wallet();
        double back = wallet == null ? 0 : wallet.roundDown(pool);
        pool = 0;
        if (back <= 0) return;
        wallet.deposit(Bukkit.getOfflinePlayer(ownerId), back);
        Player online = Bukkit.getPlayer(ownerId);
        if (online != null) Msg.info(online, "Refunded " + wallet.format(back) + " for the blocks that were not built.");
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (order.length == 0) {
            finish();
            return;
        }
        createWorkers();
        begin();
    }

    private void createWorkers() {
        int n = Math.max(1, opts.builders());
        int size = order.length;
        Range shared = new Range(0, size);
        for (int i = 0; i < n; i++) {
            Worker w = new Worker();
            switch (opts.mode()) {
                case SPLIT -> w.range = new Range((int) ((long) i * size / n), (int) ((long) (i + 1) * size / n));
                case OPPOSITE -> {
                    w.range = shared;
                    w.fromBack = i % 2 == 1;
                }
                default -> w.range = shared;
            }
            workers.add(w);
        }
        for (Worker w : workers) {
            if (!ranges.contains(w.range)) ranges.add(w.range);
        }
    }

    private void begin() {
        for (Worker w : workers) {
            int idx = w.current >= 0 ? w.current : (w.fromBack ? w.range.hi - 1 : w.range.lo);
            idx = Math.max(0, Math.min(order.length - 1, idx));
            w.npc = plugin.spawnBuilder(locOf(order[idx]));
            w.npc.setHeldItem(plugin.heldItem());
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Called after restoring a saved job. */
    void resumeAfterRestore() {
        if (order.length == 0) {
            finish();
            return;
        }
        if (workers.isEmpty()) createWorkers();
        begin();
    }

    public void pause() {
        if (state == State.RUNNING) state = State.PAUSED;
    }

    public void resume() {
        if (state == State.PAUSED) state = State.RUNNING;
    }

    public void cancel() {
        if (!isActive()) return;
        state = State.CANCELLED;
        refundPool();
        returnCarried();
        cleanup();
        plugin.saveJobs();
    }

    /** Plugin shutdown: stop and remove NPCs but keep the job state (it was saved before). */
    public void shutdown() {
        cleanup();
    }

    /** Restores every block this job changed (block entity contents are not restored). */
    public int undo() {
        cancel();
        int n = 0;
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change c = changes.get(i);
            world.getBlockAt(c.x(), c.y(), c.z()).setBlockData(c.previous(), false);
            n++;
        }
        changes.clear();
        return n;
    }

    public boolean undoTruncated() { return undoTruncated; }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        if (state != State.RUNNING) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (opts.survival() && owner == null) return; // survival builds wait for their owner
        ensureBar(owner);

        double perTick = opts.speed() / 20.0;
        int scanBudget = 8192;
        boolean checkEach = opts.survival() && plugin.mode(true).getBoolean("protection.check-each-block", true);

        for (Worker w : workers) {
            if (w.trip > 0) {
                if (--w.trip == 0) closeChest();
                continue;
            }
            w.credit = Math.min(w.credit + perTick, perTick + 2.0);
            while (scanBudget-- > 0) {
                if (w.current < 0) {
                    w.current = claim(w);
                    if (w.current < 0) break;
                }
                int idx = order[w.current];
                Block block = blockOf(idx);
                BlockData target = bp.data(idx);

                if (skip(block, target)) {
                    w.current = -1;
                    processed++;
                    continue;
                }
                if (w.credit < 1.0) break;
                if (checkEach && !Protection.canBuild(owner, block)) {
                    denied++;
                    w.current = -1;
                    processed++;
                    continue;
                }
                double cost = blockCost(target);
                if (!ensureFunds(owner, cost)) return; // paused: out of funds
                PayResult pay = pay(w, owner, target);
                if (pay == PayResult.TRIP) break;
                if (pay == PayResult.MISSING) {
                    warnMissing(owner, target);
                    break;
                }
                place(w, block, target);
                pool -= Math.min(pool, cost);
                w.credit -= 1.0;
                w.current = -1;
                processed++;
                placed++;
            }
        }

        if (++tickCount % 10 == 0) updateBar();
        for (Worker w : workers) {
            if (w.current >= 0 || w.range.lo < w.range.hi) return;
        }
        finish();
    }

    private static int claim(Worker w) {
        Range r = w.range;
        if (r.lo >= r.hi) return -1;
        return w.fromBack ? --r.hi : r.lo++;
    }

    private boolean skip(Block block, BlockData target) {
        Material t = target.getMaterial();
        if (t == Material.STRUCTURE_VOID) return true;
        if (t.isAir()) {
            if (opts.ignoreAir()) return true;
            if (block.getType().isAir()) return true;
        }
        if (opts.ignoreWater() && t == Material.WATER) return true;
        return block.getBlockData().equals(target);
    }

    private void place(Worker w, Block block, BlockData target) {
        if (changes.size() < maxUndo) {
            changes.add(new Change(block.getX(), block.getY(), block.getZ(), block.getBlockData()));
        } else {
            undoTruncated = true;
        }
        // physics off: doors, beds, torches and tall plants cannot pop off while their other half or support is still missing
        block.setBlockData(target, false);

        Location c = block.getLocation().add(0.5, 0.5, 0.5);
        if (plugin.getConfig().getBoolean("effects", true) && !target.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, c, 8, 0.25, 0.25, 0.25, target);
            world.playSound(c, target.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.6f, 0.9f);
        }
        if (w.npc != null && w.npc.isValid()) {
            w.npc.moveTo(block.getLocation().add(0.5, 1.0, 0.5));
            w.npc.swing();
        }
    }

    private Block blockOf(int idx) {
        int w = bp.width(), l = bp.length();
        int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
        return world.getBlockAt(ox + x, oy + y, oz + z);
    }

    private Location locOf(int idx) {
        int w = bp.width(), l = bp.length();
        int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
        return new Location(world, ox + x + 0.5, oy + y + 1.0, oz + z + 0.5);
    }

    // ------------------------------------------------------------------ materials

    /** Item needed to place this block, or null if it is free (air, fluids, second halves...). */
    static Material costOf(BlockData d) {
        Material type = d.getMaterial();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN || type == Material.STRUCTURE_VOID) return null;
        if (d instanceof Door door && door.getHalf() == Bisected.Half.TOP) return null;
        if (d instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) return null;
        if (TALL.contains(type) && d instanceof Bisected tall && tall.getHalf() == Bisected.Half.TOP) return null;
        Material m = d.getPlacementMaterial();
        return m.isItem() ? m : null;
    }

    static int amountOf(BlockData d) {
        return (d instanceof Slab s && s.getType() == Slab.Type.DOUBLE) ? 2 : 1;
    }

    private PayResult pay(Worker w, Player owner, BlockData target) {
        if (opts.supply() == Supply.FREE || opts.supply() == Supply.CONTRACT) return PayResult.OK;
        Material m = costOf(target);
        if (m == null) return PayResult.OK;
        int amount = amountOf(target);

        if (opts.supply() == Supply.INVENTORY) {
            if (owner == null) return PayResult.MISSING;
            if (!opts.survival() && owner.getGameMode() == GameMode.CREATIVE
                    && plugin.mode(false).getBoolean("free-in-creative", true)) return PayResult.OK;
            ItemStack stack = new ItemStack(m, amount);
            if (!owner.getInventory().containsAtLeast(stack, amount)) return PayResult.MISSING;
            owner.getInventory().removeItem(stack);
            return PayResult.OK;
        }

        int have = w.carried.getOrDefault(m, 0);
        if (have >= amount) {
            w.carried.put(m, have - amount);
            return PayResult.OK;
        }
        return fetch(w, m);
    }

    /** The builder walks to the supply chest, opens it, and takes up to a stack of the needed block. */
    private PayResult fetch(Worker w, Material m) {
        if (chest == null) return PayResult.MISSING;
        Block cb = chest.getBlock();
        BlockState st = cb.getState();
        if (!(st instanceof Container container)) return PayResult.MISSING;
        int got = take(container.getInventory(), m, m.getMaxStackSize());
        if (got <= 0) return PayResult.MISSING;
        w.carried.merge(m, got, Integer::sum);
        w.trip = 16;
        Compat.lid(cb, true);
        if (w.npc != null && w.npc.isValid()) {
            w.npc.moveTo(cb.getLocation().add(0.5, 1.0, 0.5));
            w.npc.swing();
        }
        return PayResult.TRIP;
    }

    private static int take(Inventory inv, Material m, int want) {
        int got = 0;
        for (int i = 0; i < inv.getSize() && got < want; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m || it.hasItemMeta()) continue;
            int t = Math.min(it.getAmount(), want - got);
            if (t == it.getAmount()) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - t);
            got += t;
        }
        return got;
    }

    private void closeChest() {
        if (chest != null) Compat.lid(chest.getBlock(), false);
    }

    private void returnCarried() {
        if (chest == null) return;
        BlockState st = chest.getBlock().getState();
        for (Worker w : workers) {
            for (Map.Entry<Material, Integer> e : w.carried.entrySet()) {
                if (e.getValue() <= 0) continue;
                ItemStack stack = new ItemStack(e.getKey(), e.getValue());
                if (st instanceof Container c) {
                    for (ItemStack rest : c.getInventory().addItem(stack).values()) {
                        world.dropItemNaturally(chest, rest);
                    }
                } else {
                    world.dropItemNaturally(chest, stack);
                }
            }
            w.carried.clear();
        }
    }

    private void warnMissing(Player owner, BlockData d) {
        long now = System.currentTimeMillis();
        if (owner == null || now - lastWarn < 10_000) return;
        lastWarn = now;
        Material m = costOf(d);
        String name = m == null ? "?" : AutoBuilderPlugin.pretty(m);
        Msg.error(owner, opts.supply() == Supply.CHEST
                ? "Builders are waiting: the supply chest has no " + name + ". Fill it up (or /ab cancel)."
                : "Builders are waiting: you need " + name + " in your inventory (or /ab cancel).");
    }

    // ------------------------------------------------------------------ boss bar, finish

    private void ensureBar(Player owner) {
        if (bar != null || owner == null || !plugin.getConfig().getBoolean("boss-bar", true)) return;
        bar = BossBar.bossBar(Component.text("Building..."), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        owner.showBossBar(bar);
    }

    private void updateBar() {
        if (bar == null) return;
        float p = order.length == 0 ? 1f : Math.min(1f, (float) processed / order.length);
        bar.progress(p);
        bar.name(Component.text(String.format("Building: %s [%d%% - %,d / %,d blocks]",
                bp.name(), (int) (p * 100), processed, order.length)));
    }

    private void finish() {
        state = State.DONE;
        stopTask();
        hideBar();
        refundPool();
        returnCarried();
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null) {
            Msg.info(owner, "Build finished: " + placed + " blocks placed, "
                    + (processed - placed - denied) + " skipped (already correct), " + denied
                    + " blocked by protection. /ab undo reverts it.");
        }
        boolean any = celebrate();
        if (any) Bukkit.getScheduler().runTaskLater(plugin, this::removeNpcs, 60L);
        else removeNpcs();
        plugin.saveJobs();
    }

    /** Happy-villager burst and a level-up sound at every builder when the build is done. */
    private boolean celebrate() {
        if (!plugin.getConfig().getBoolean("celebration", true)) return false;
        boolean any = false;
        for (Worker w : workers) {
            if (w.npc == null || !w.npc.isValid() || w.npc.entity() == null) continue;
            Location l = w.npc.entity().getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.HAPPY_VILLAGER, l, 25, 0.6, 0.6, 0.6, 0.0);
            world.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.0f);
            any = true;
        }
        return any;
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void hideBar() {
        if (bar != null) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) owner.hideBossBar(bar);
            bar = null;
        }
    }

    private void removeNpcs() {
        for (Worker w : workers) {
            if (w.npc != null) {
                w.npc.remove();
                w.npc = null;
            }
        }
    }

    private void cleanup() {
        stopTask();
        hideBar();
        removeNpcs();
    }

    // ------------------------------------------------------------------ persistence (jobs.yml)

    void save(ConfigurationSection sec) {
        sec.set("file", bp.fileName());
        sec.set("rotation", rotation);
        sec.set("world", world.getName());
        sec.set("origin", List.of(ox, oy, oz));
        if (chest != null) {
            sec.set("chest", List.of(chest.getBlockX(), chest.getBlockY(), chest.getBlockZ()));
        }
        sec.set("state", state.name());
        sec.set("processed", processed);
        sec.set("placed", placed);
        sec.set("denied", denied);
        sec.set("pool", pool);
        sec.set("options.ignoreAir", opts.ignoreAir());
        sec.set("options.ignoreWater", opts.ignoreWater());
        sec.set("options.supply", opts.supply().name());
        sec.set("options.builders", opts.builders());
        sec.set("options.speed", opts.speed());
        sec.set("options.pattern", opts.pattern().name());
        sec.set("options.mode", opts.mode().name());
        sec.set("options.sweepX", opts.sweepX());
        sec.set("options.sweepZ", opts.sweepZ());
        sec.set("options.survival", opts.survival());
        sec.set("options.labor", opts.labor());

        List<String> rs = new ArrayList<>();
        for (Range r : ranges) rs.add(r.lo + "," + r.hi);
        sec.set("ranges", rs);

        List<String> ws = new ArrayList<>();
        List<String> cs = new ArrayList<>();
        for (int i = 0; i < workers.size(); i++) {
            Worker w = workers.get(i);
            ws.add(ranges.indexOf(w.range) + "," + w.fromBack + "," + w.current);
            for (Map.Entry<Material, Integer> e : w.carried.entrySet()) {
                if (e.getValue() > 0) cs.add(i + ":" + e.getKey().name() + ":" + e.getValue());
            }
        }
        sec.set("workers", ws);
        sec.set("carried", cs);
    }

    static BuildJob restore(AutoBuilderPlugin plugin, UUID owner, ConfigurationSection sec) throws IOException {
        ConfigurationSection os = sec.getConfigurationSection("options");
        if (os == null) throw new IOException("options are missing");
        boolean survival = os.getBoolean("survival");

        File f = plugin.findFile(sec.getString("file", ""), survival);
        if (f == null) throw new IOException("schematic file is missing");
        World world = Bukkit.getWorld(sec.getString("world", ""));
        if (world == null) throw new IOException("world is not loaded");
        Blueprint source = Blueprint.load(f, plugin.maxVolume());

        List<Integer> o = sec.getIntegerList("origin");
        Location origin = new Location(world, o.get(0), o.get(1), o.get(2));
        Location chest = null;
        List<Integer> c = sec.getIntegerList("chest");
        if (c.size() == 3) chest = new Location(world, c.get(0), c.get(1), c.get(2));

        Options opts = new Options(os.getBoolean("ignoreAir"), os.getBoolean("ignoreWater"),
                Supply.valueOf(os.getString("supply", "FREE")), os.getInt("builders", 1),
                os.getDouble("speed", 10), Pattern.valueOf(os.getString("pattern", "UP")),
                Mode.valueOf(os.getString("mode", "SHARED")), os.getInt("sweepX"), os.getInt("sweepZ"),
                survival, os.getDouble("labor"));

        BuildJob job = new BuildJob(plugin, owner, origin, source, sec.getInt("rotation"), opts, chest);
        job.processed = sec.getInt("processed");
        job.placed = sec.getInt("placed");
        job.denied = sec.getInt("denied");
        job.pool = sec.getDouble("pool");
        job.state = State.valueOf(sec.getString("state", "RUNNING"));

        for (String s : sec.getStringList("ranges")) {
            String[] p = s.split(",");
            job.ranges.add(new Range(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
        }
        for (String s : sec.getStringList("workers")) {
            String[] p = s.split(",");
            Worker w = new Worker();
            w.range = job.ranges.get(Integer.parseInt(p[0]));
            w.fromBack = Boolean.parseBoolean(p[1]);
            w.current = Integer.parseInt(p[2]);
            job.workers.add(w);
        }
        for (String s : sec.getStringList("carried")) {
            String[] p = s.split(":");
            job.workers.get(Integer.parseInt(p[0])).carried.merge(Material.valueOf(p[1]), Integer.parseInt(p[2]), Integer::sum);
        }
        return job;
    }
}
