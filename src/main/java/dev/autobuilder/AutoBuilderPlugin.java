package dev.autobuilder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

public final class AutoBuilderPlugin extends JavaPlugin {

    private static final String[] EXTENSIONS = {"", ".schem", ".litematic", ".nbt"};
    private static final String ADMIN = "ronautobuilder.admin";
    private static final String BYPASS = "ronautobuilder.economy.bypass";

    /** One row of the material checklist. */
    record MatLine(Material material, long need, long have) {}

    private final Map<UUID, Session> sessions = new HashMap<>();
    private File adminDir, playerDir;
    private YamlConfiguration survivalCfg, adminCfg, ecoCfg, hireCfg, shopCfg;
    private ShopPrices shop;
    private Wallet wallet;
    private boolean walletTried, walletWarned, npcWarned, jobsLoaded;
    private BuilderGui gui;
    private Preview preview;
    private NpcManager npcs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setup();
        gui = new BuilderGui(this);
        preview = new Preview(this);
        npcs = new NpcManager(this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(preview, this);
        Bukkit.getPluginManager().registerEvents(npcs, this);

        BuildCommand handler = new BuildCommand(this);
        PluginCommand cmd = getCommand("ronautobuilder");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new ExpansionHook(this).register();
                getLogger().info("PlaceholderAPI placeholders registered (%autobuilder_status% and more).");
            } catch (Throwable t) {
                getLogger().warning("Could not register the PlaceholderAPI expansion: " + t);
            }
        }

        // wait a moment so all worlds and the economy plugin are ready
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().info("Builder NPCs: " + (MannequinBuilder.available()
                    ? "player-model (Mannequin)" : "armor stand (server is older than 1.21.9)"));
            if (ecoCfg.getBoolean("enabled", false) && wallet() == null) {
                getLogger().warning("economy.yml is enabled but no payment method could be set up.");
            }
            sweepOrphans();
            npcs.load();
            loadJobs();
        }, 40L);
        long period = Math.max(5, getConfig().getInt("persistence.autosave-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::saveJobs, period, period);
    }

    @Override
    public void onDisable() {
        saveJobs();
        for (Session s : sessions.values()) {
            if (s.job != null) s.job.shutdown();
        }
        sessions.clear();
        if (preview != null) preview.clearAll();
        if (npcs != null) npcs.shutdown();
    }

    private void setup() {
        survivalCfg = loadYaml("survival.yml");
        adminCfg = loadYaml("admin.yml");
        ecoCfg = loadYaml("economy.yml");
        hireCfg = loadYaml("hire.yml");
        shopCfg = loadYaml("shop-prices.yml");
        shop = new ShopPrices(shopCfg);
        adminDir = new File(getDataFolder(), adminCfg.getString("schematics-folder", "schematics/admin"));
        playerDir = new File(getDataFolder(), survivalCfg.getString("schematics-folder", "schematics/players"));
        adminDir.mkdirs();
        playerDir.mkdirs();
        wallet = null;
        walletTried = false;
    }

    private YamlConfiguration loadYaml(String file) {
        File f = new File(getDataFolder(), file);
        if (!f.exists()) saveResource(file, false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = getResource(file)) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // built-in defaults are optional
        }
        return cfg;
    }

    void reload() {
        reloadConfig();
        setup();
    }

    /** Orphaned builder NPCs (for example left by a crash) are removed on startup. */
    private void sweepOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) {
                if (e.getScoreboardTags().contains(MannequinBuilder.TAG)
                        && !MannequinBuilder.LIVE.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    BuilderGui gui() { return gui; }
    Preview preview() { return preview; }
    NpcManager npcs() { return npcs; }
    ShopPrices shop() { return shop; }
    FileConfiguration eco() { return ecoCfg; }
    int maxVolume() { return getConfig().getInt("max-volume", 40000000); }

    /** Settings of one mode: survival.yml or admin.yml (same keys, separate files). */
    FileConfiguration mode(boolean survival) {
        return survival ? survivalCfg : adminCfg;
    }

    boolean isAdmin(Player p) {
        return p.hasPermission(ADMIN);
    }

    /** Survival view = normal players, or admins who switched to it with /ab survival. */
    boolean isSurvival(Player p, Session s) {
        return !(isAdmin(p) && s.adminView);
    }

    // ------------------------------------------------------------------ economy

    /** The payment method from economy.yml, or null when the economy is off or unavailable. */
    Wallet wallet() {
        if (!ecoCfg.getBoolean("enabled", false)) return null;
        if (!walletTried) {
            walletTried = true;
            String type = ecoCfg.getString("currency.type", "VAULT").trim().toUpperCase(Locale.ROOT);
            if (type.equals("ITEM")) {
                Material m = Material.matchMaterial(ecoCfg.getString("currency.item", "DIAMOND"));
                if (m != null && m.isItem() && !m.isAir()) wallet = new ItemWallet(m);
                else getLogger().warning("economy.yml: currency.item is not a valid item.");
            } else {
                wallet = VaultWallet.create(this);
                if (wallet == null && !walletWarned) {
                    walletWarned = true;
                    getLogger().warning("economy.yml: Vault or an economy plugin was not found.");
                }
            }
            if (wallet != null) getLogger().info("Economy active: " + wallet.name());
        }
        return wallet;
    }

    boolean economyOn() {
        return wallet() != null;
    }

    boolean contractAllowed() {
        return economyOn() && ecoCfg.getBoolean("contract.enabled", true);
    }

    private NavigableMap<Integer, Double> tiers(String section, int fallbackKey) {
        TreeMap<Integer, Double> out = new TreeMap<>();
        ConfigurationSection sec = hireCfg.getConfigurationSection(section);
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    out.put(Integer.parseInt(key.trim()), Math.max(0, sec.getDouble(key)));
                } catch (NumberFormatException ignored) {
                    // not a number: skip
                }
            }
        }
        if (out.isEmpty()) out.put(fallbackKey, 0.0);
        return out;
    }

    NavigableMap<Integer, Double> builderTiers() {
        return tiers("builders", 1);
    }

    NavigableMap<Integer, Double> speedTiers() {
        return tiers("speed", 5);
    }

    /** A tier can be used when it is within the cap and either free or the economy is active. */
    boolean tierAllowed(int key, double price, double cap) {
        return key <= cap && (economyOn() || price <= 0);
    }

    private int snap(NavigableMap<Integer, Double> tiers, double value, double cap) {
        int best = tiers.firstKey();
        for (Map.Entry<Integer, Double> e : tiers.entrySet()) {
            if (e.getKey() <= value && tierAllowed(e.getKey(), e.getValue(), cap)) best = Math.max(best, e.getKey());
        }
        return best;
    }

    int maxBuilders(boolean survival) {
        int cap = mode(survival).getInt("max-builders", 4);
        if (!survival) return cap;
        int best = builderTiers().firstKey();
        for (Map.Entry<Integer, Double> e : builderTiers().entrySet()) {
            if (tierAllowed(e.getKey(), e.getValue(), cap)) best = Math.max(best, e.getKey());
        }
        return best;
    }

    double maxSpeed(boolean survival) {
        double cap = mode(survival).getDouble("max-speed", survival ? 20 : 100);
        if (!survival) return cap;
        int best = speedTiers().firstKey();
        for (Map.Entry<Integer, Double> e : speedTiers().entrySet()) {
            if (tierAllowed(e.getKey(), e.getValue(), cap)) best = Math.max(best, e.getKey());
        }
        return best;
    }

    private double hireFee(int builders, int speed) {
        return builderTiers().getOrDefault(builders, 0.0) + speedTiers().getOrDefault(speed, 0.0);
    }

    private boolean paysFor(Player p, Session s) {
        return isSurvival(p, s) && economyOn() && !p.hasPermission(BYPASS);
    }

    /** Hire fee of the next build as text, or null when nothing is charged. */
    String hireSummary(Player p, Session s) {
        if (!paysFor(p, s)) return null;
        int b = snap(builderTiers(), s.builders, survivalCfg.getInt("max-builders", 4));
        int sp = snap(speedTiers(), s.speed, survivalCfg.getDouble("max-speed", 20));
        return wallet().format(hireFee(b, sp));
    }

    private String priceText(double price) {
        return economyOn() && price > 0 ? " - " + wallet().format(price) + " per build" : "";
    }

    void selectBuilders(Player p, Session s, int n) {
        if (!isSurvival(p, s)) {
            s.builders = Math.max(1, Math.min(n, maxBuilders(false)));
            Msg.info(p, s.builders + " builder(s) selected.");
            return;
        }
        Double price = builderTiers().get(n);
        if (price == null || !tierAllowed(n, price, survivalCfg.getInt("max-builders", 4))) {
            Msg.error(p, n + " builders are not available" + (economyOn() ? "." : " (economy is off)."));
            return;
        }
        s.builders = n;
        Msg.info(p, n + " builder(s) selected" + priceText(price) + ".");
    }

    void selectSpeed(Player p, Session s, int n) {
        if (!isSurvival(p, s)) {
            s.speed = Math.max(1, Math.min(n, maxSpeed(false)));
            Msg.info(p, "Speed " + (int) s.speed + " blocks/s selected.");
            return;
        }
        Double price = speedTiers().get(n);
        if (price == null || !tierAllowed(n, price, survivalCfg.getDouble("max-speed", 20))) {
            Msg.error(p, "Speed " + n + " is not available" + (economyOn() ? "." : " (economy is off)."));
            return;
        }
        s.speed = n;
        Msg.info(p, "Speed " + n + " blocks/s selected" + priceText(price) + ".");
    }

    // ------------------------------------------------------------------ sessions

    private static BuildJob.Supply parseSupply(String s, BuildJob.Supply fallback) {
        try {
            return BuildJob.Supply.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    Session session(UUID id) {
        return sessions.computeIfAbsent(id, k -> {
            Player p = Bukkit.getPlayer(id);
            boolean admin = p != null && isAdmin(p);
            FileConfiguration m = mode(!admin);
            Session s = new Session(m.getDouble("speed", admin ? 10 : 5));
            BuildJob.Supply fallback = admin ? BuildJob.Supply.FREE : BuildJob.Supply.INVENTORY;
            s.supply = parseSupply(m.getString("supply.default", fallback.name()), fallback);
            if (admin) s.ignoreAir = m.getBoolean("ignore-air", false);
            return s;
        });
    }

    Session sessionIfPresent(UUID id) {
        return sessions.get(id);
    }

    // ------------------------------------------------------------------ builders

    BuilderNpc spawnBuilder(Location loc) {
        return spawnBuilder(loc, getConfig().getString("builder.name", "Builder"), null);
    }

    /** A builder NPC; skinOverride (a username) wins over the skin set in config.yml. */
    BuilderNpc spawnBuilder(Location loc, String name, String skinOverride) {
        FileConfiguration cfg = getConfig();
        boolean override = skinOverride != null && !skinOverride.isBlank();
        String skin = override ? skinOverride : cfg.getString("builder.skin.name", "");
        String texture = override ? "" : cfg.getString("builder.skin.texture", "");
        String signature = override ? "" : cfg.getString("builder.skin.signature", "");
        if (MannequinBuilder.available()) {
            try {
                return new MannequinBuilder(loc, name, skin, texture, signature);
            } catch (RuntimeException ex) {
                if (!npcWarned) {
                    npcWarned = true;
                    getLogger().warning("Could not spawn a player-model NPC (" + ex + "). Using armor stands.");
                }
            }
        }
        return new StandBuilder(loc, name, skin, texture, signature);
    }

    ItemStack heldItem() {
        String name = getConfig().getString("builder.held-item", "IRON_PICKAXE");
        Material m = Material.matchMaterial(name == null ? "" : name);
        if (m == null || m.isAir() || !m.isItem()) return null;
        return new ItemStack(m);
    }

    // ------------------------------------------------------------------ files (separate folders per mode)

    static boolean safeName(String n) {
        return n != null && !n.isEmpty() && !n.contains("..") && !n.contains("/") && !n.contains("\\");
    }

    /** Admins use the admin folder, survival players the players folder. */
    File schematicDir(boolean survival) {
        return survival ? playerDir : adminDir;
    }

    File findFile(String name, boolean survival) {
        if (!safeName(name)) return null;
        for (String ext : EXTENSIONS) {
            File f = new File(schematicDir(survival), name + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    List<String> fileNames(boolean survival) {
        List<String> out = new ArrayList<>();
        File[] files = schematicDir(survival).listFiles(
                (d, n) -> n.endsWith(".schem") || n.endsWith(".litematic") || n.endsWith(".nbt"));
        if (files != null) {
            for (File f : files) out.add(f.getName());
        }
        Collections.sort(out);
        return out;
    }

    static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ shared actions (commands + GUI)

    boolean load(Player p, Session s, String name) {
        File f = findFile(name, isSurvival(p, s));
        if (f == null) {
            Msg.error(p, "Schematic not found: " + name);
            return false;
        }
        try {
            Blueprint b = Blueprint.load(f, maxVolume());
            s.blueprint = b;
            s.rotation = 0;
            Msg.info(p, "Loaded " + b.name() + ": " + b.width() + "x" + b.height() + "x" + b.length()
                    + ", " + b.blockCount() + " blocks.");
            if (b.unknown() > 0) {
                Msg.error(p, b.unknown() + " block type(s) are unknown to this server version and will be skipped.");
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            Msg.error(p, "Could not load " + f.getName() + ": " + ex.getMessage());
            getLogger().warning("Failed to load " + f + ": " + ex);
            return false;
        } catch (OutOfMemoryError oom) {
            Msg.error(p, "Not enough server memory to load " + f.getName() + ".");
            return false;
        }
    }

    private boolean originAllowed(Player p, Session s, Block b) {
        if (isSurvival(p, s) && survivalCfg.getBoolean("protection.check-origin", true) && !Protection.canBuild(p, b)) {
            Msg.error(p, "You can't build there (protected area).");
            return false;
        }
        return true;
    }

    void setOriginHere(Player p, Session s) {
        Block b = p.getLocation().getBlock();
        if (!originAllowed(p, s, b)) return;
        s.origin = b.getLocation();
        originSet(p, s);
    }

    void setOriginLook(Player p, Session s) {
        Block t = p.getTargetBlockExact(64);
        if (t == null) {
            Msg.error(p, "Not looking at a block.");
            return;
        }
        Block above = t.getRelative(0, 1, 0);
        if (!originAllowed(p, s, above)) return;
        s.origin = above.getLocation();
        originSet(p, s);
    }

    private void originSet(Player p, Session s) {
        Msg.info(p, "Origin (lowest corner) set to " + s.origin.getBlockX() + ", "
                + s.origin.getBlockY() + ", " + s.origin.getBlockZ() + ".");
        preview.refresh(p, s);
    }

    boolean ready(Player p, Session s) {
        if (s.blueprint == null) {
            Msg.error(p, "Load a schematic first (/ab gui or /ab load <name>).");
            return false;
        }
        if (s.origin == null) {
            Msg.error(p, "Set an origin first (/ab origin).");
            return false;
        }
        return true;
    }

    void linkChest(Player p, Session s) {
        Block t = p.getTargetBlockExact(8);
        if (t == null || !(t.getState() instanceof Container)) {
            Msg.error(p, "Look at a chest or barrel (within 8 blocks) and try again.");
            return;
        }
        if (!isAdmin(p) && !Protection.canOpen(p, t)) {
            Msg.error(p, "You can't open that container.");
            return;
        }
        s.chest = t.getLocation();
        s.supply = BuildJob.Supply.CHEST;
        Msg.info(p, "Supply chest linked at " + t.getX() + ", " + t.getY() + ", " + t.getZ()
                + ". Builders will fetch blocks from it; keep it filled.");
    }

    private static int[] sweep(Player p, BuildJob.Pattern pattern) {
        // yaw 0 = facing south (+z), 90 = west, 180 = north, 270 = east
        int q = ((Math.round(p.getLocation().getYaw() / 90f) % 4) + 4) % 4;
        int[] fx = {0, -1, 0, 1};
        int[] fz = {1, 0, -1, 0};
        int rx = -fz[q], rz = fx[q]; // the player's right-hand side
        return switch (pattern) {
            case LEFT -> new int[]{rx, rz};       // start on the left, move right
            case RIGHT -> new int[]{-rx, -rz};    // start on the right, move left
            case NORTH -> new int[]{0, 1};
            case SOUTH -> new int[]{0, -1};
            case EAST -> new int[]{-1, 0};
            case WEST -> new int[]{1, 0};
            default -> new int[]{0, 0};
        };
    }

    private boolean protectionOk(Player p, Session s) {
        int w = s.blueprint.sizeX(s.rotation), l = s.blueprint.sizeZ(s.rotation);
        int x0 = s.origin.getBlockX(), z0 = s.origin.getBlockZ(), y = s.origin.getBlockY();
        for (int cx = x0 >> 4; cx <= (x0 + w - 1) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + l - 1) >> 4; cz++) {
                int bx = Math.max(x0, cx << 4), bz = Math.max(z0, cz << 4);
                if (!Protection.canBuild(p, s.origin.getWorld().getBlockAt(bx, y, bz))) {
                    Msg.error(p, "Part of this build is inside a protected area (near " + bx + ", " + bz + ").");
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ starting a build

    void startBuild(Player p, Session s) {
        if (!ready(p, s)) return;
        if (s.job != null && s.job.isActive()) {
            Msg.error(p, "You already have a build running (pause or cancel it first).");
            return;
        }
        if (s.job != null && s.job.state() == BuildJob.State.DONE) s.hireKey = null;
        boolean survival = isSurvival(p, s);
        FileConfiguration m = mode(survival);
        if (survival && !m.getBoolean("enabled", true)) {
            Msg.error(p, "Survival building is disabled on this server.");
            return;
        }
        int maxBlocks = m.getInt("max-blocks", survival ? 20000 : 500000);
        if (s.blueprint.blockCount() > maxBlocks) {
            Msg.error(p, "This build has " + s.blueprint.blockCount() + " blocks; your limit is " + maxBlocks + ".");
            return;
        }

        int builders;
        double speed;
        if (survival) {
            builders = snap(builderTiers(), s.builders, m.getInt("max-builders", 4));
            speed = snap(speedTiers(), s.speed, m.getDouble("max-speed", 20));
        } else {
            builders = Math.max(1, Math.min(s.builders, maxBuilders(false)));
            speed = Math.max(1, Math.min(s.speed, maxSpeed(false)));
        }

        boolean bypass = p.hasPermission(BYPASS);
        boolean pays = paysFor(p, s);
        BuildJob.Supply supply = s.supply;
        if (supply == BuildJob.Supply.CONTRACT) {
            if (!survival || bypass) {
                supply = BuildJob.Supply.FREE;
            } else if (!contractAllowed()) {
                Msg.error(p, "Contract builds are not available on this server. Use /ab supply inventory or chest.");
                return;
            }
        }
        if (survival && !bypass && supply == BuildJob.Supply.FREE) supply = BuildJob.Supply.INVENTORY;

        Location chest = null;
        if (supply == BuildJob.Supply.CHEST) {
            chest = s.chest;
            if (chest == null || !(chest.getBlock().getState() instanceof Container)) {
                Msg.error(p, "Link a supply chest first: look at a chest or barrel and use /ab chest.");
                return;
            }
            double maxDist = m.getDouble("supply.chest-max-distance", 16);
            if (survival && (!chest.getWorld().equals(s.origin.getWorld()) || chest.distance(s.origin) > maxDist)) {
                Msg.error(p, "The supply chest must be within " + (int) maxDist + " blocks of the origin.");
                return;
            }
        }
        boolean ignoreAir = survival ? m.getBoolean("ignore-air", true) : s.ignoreAir;
        if (survival && m.getBoolean("protection.check-origin", true) && !protectionOk(p, s)) return;

        String key = s.blueprint.fileName() + "@" + s.origin.getBlockX() + "," + s.origin.getBlockY() + ","
                + s.origin.getBlockZ() + "@" + s.rotation;
        double labor = pays ? ecoCfg.getDouble("labor-per-block", 0) : 0;
        double hire = pays && !key.equals(s.hireKey) ? hireFee(builders, (int) speed) : 0;

        int[] dir = sweep(p, s.pattern);
        BuildJob.Options opts = new BuildJob.Options(ignoreAir, s.ignoreWater, supply, builders, speed,
                s.pattern, s.mode, dir[0], dir[1], survival, labor);
        BuildJob job;
        BuildJob.Quote quote;
        try {
            job = new BuildJob(this, p.getUniqueId(), s.origin, s.blueprint, s.rotation, opts, chest);
            quote = job.quote(hire);
        } catch (RuntimeException ex) {
            Msg.error(p, "Could not prepare the build: " + ex.getMessage());
            getLogger().warning("Build preparation failed: " + ex);
            return;
        }

        if (!pays || (quote.upfront() <= 0 && quote.payAsYouGo() <= 0)) {
            launch(p, s, job);
            return;
        }
        s.pending = job;
        s.pendingQuote = quote;
        s.pendingKey = key;
        sendQuote(p, quote);
        if (ecoCfg.getBoolean("confirm", true)) gui.openConfirm(p);
        else confirmBuild(p, s);
    }

    private void sendQuote(Player p, BuildJob.Quote q) {
        Wallet w = wallet();
        if (w == null) return;
        Msg.info(p, "Price for " + q.blocks() + " block(s) still to place:");
        if (q.hire() > 0) Msg.info(p, "  Hiring: " + w.format(q.hire()));
        if (q.materials() > 0) Msg.info(p, "  Materials: " + w.format(q.materials()));
        if (q.labor() > 0) Msg.info(p, "  Labor: " + w.format(q.labor()));
        if (q.upfront() > 0) Msg.info(p, "  Pay now: " + w.format(q.upfront()));
        if (q.payAsYouGo() > 0) Msg.info(p, "  Pay while building: about " + w.format(q.payAsYouGo()));
        Msg.info(p, "Confirm in the menu or with /ab confirm. /ab deny cancels without charge.");
    }

    void confirmBuild(Player p, Session s) {
        BuildJob job = s.pending;
        BuildJob.Quote q = s.pendingQuote;
        if (job == null || q == null) {
            Msg.error(p, "There is nothing to confirm.");
            return;
        }
        String key = s.pendingKey;
        s.pending = null;
        s.pendingQuote = null;
        s.pendingKey = null;
        if (s.job != null && s.job.isActive()) {
            Msg.error(p, "You already have a build running (pause or cancel it first).");
            return;
        }
        Wallet w = wallet();
        if (q.upfront() > 0) {
            if (w == null || !w.withdraw(p, q.upfront())) {
                Msg.error(p, "You can't afford this build" + (w == null ? "." : " (" + w.format(q.upfront()) + ")."));
                return;
            }
            job.prepay(q.pool());
            Msg.info(p, "Paid " + w.format(q.upfront()) + ".");
            if (q.hire() > 0) s.hireKey = key;
        }
        launch(p, s, job);
    }

    void denyBuild(Player p, Session s) {
        if (s.pending == null) {
            Msg.error(p, "There is nothing to cancel.");
            return;
        }
        s.pending = null;
        s.pendingQuote = null;
        s.pendingKey = null;
        Msg.info(p, "Build not started. Nothing was charged.");
    }

    private void launch(Player p, Session s, BuildJob job) {
        s.job = job;
        try {
            job.start();
        } catch (RuntimeException ex) {
            job.cancel();
            Msg.error(p, "Could not start the builders: " + ex.getMessage());
            getLogger().warning("Builder spawn failed: " + ex);
            return;
        }
        Msg.info(p, "Building " + s.blueprint.name() + " (mode " + s.mode + ", pattern " + s.pattern + ").");
        saveJobs();
    }

    // ------------------------------------------------------------------ material checklist

    private static long count(Inventory inv, Material m) {
        long n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    private Inventory chestInventory(Session s) {
        if (s.supply == BuildJob.Supply.CHEST && s.chest != null
                && s.chest.getBlock().getState() instanceof Container c) {
            return c.getInventory();
        }
        return null;
    }

    /** Every block type the loaded schematic needs, with how many the player (and linked chest) has. */
    List<MatLine> materialLines(Player p, Session s) {
        List<MatLine> out = new ArrayList<>();
        if (s.blueprint == null) return out;
        long[] counts = s.blueprint.paletteCounts();
        Map<Material, Long> need = new EnumMap<>(Material.class);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            BlockData d = s.blueprint.state(i);
            Material m = BuildJob.costOf(d);
            if (m == null) continue;
            need.merge(m, counts[i] * BuildJob.amountOf(d), Long::sum);
        }
        Inventory chestInv = chestInventory(s);
        for (Map.Entry<Material, Long> e : need.entrySet()) {
            Material m = e.getKey();
            long have = count(p.getInventory(), m) + (chestInv != null ? count(chestInv, m) : 0);
            out.add(new MatLine(m, e.getValue(), have));
        }
        out.sort((a, b) -> Long.compare(b.need(), a.need()));
        return out;
    }

    /** Chat version of the checklist (/ab materials). */
    void sendMaterials(Player p, Session s, int page) {
        if (s.blueprint == null) {
            Msg.error(p, "Load a schematic first.");
            return;
        }
        List<MatLine> list = materialLines(p, s);
        int per = 12;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        boolean noItems = s.supply == BuildJob.Supply.FREE || s.supply == BuildJob.Supply.CONTRACT;
        Msg.info(p, "Materials for " + s.blueprint.name() + " (page " + (pg + 1) + "/" + pages + ")"
                + (noItems ? " - the builders bring the blocks, you don't need them" : ""));
        for (int i = pg * per; i < Math.min(list.size(), (pg + 1) * per); i++) {
            MatLine l = list.get(i);
            int stack = l.material().getMaxStackSize();
            String stacks = l.need() >= stack ? " (" + (l.need() / stack) + " stacks + " + (l.need() % stack) + ")" : "";
            p.sendMessage(Component.text(pretty(l.material()) + ": " + l.need() + stacks,
                            l.have() >= l.need() ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text("  [" + l.have() + " available]", NamedTextColor.GRAY)));
        }
        if (pages > 1) Msg.info(p, "Next page: /ab materials " + (pg + 2));
    }

    // ------------------------------------------------------------------ jobs.yml (crash / restart recovery)

    void saveJobs() {
        if (!jobsLoaded || !getConfig().getBoolean("persistence.enabled", true)) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            BuildJob j = e.getValue().job;
            if (j != null && j.isActive()) j.save(yml.createSection("jobs." + e.getKey()));
        }
        try {
            yml.save(new File(getDataFolder(), "jobs.yml"));
        } catch (IOException ex) {
            getLogger().warning("Could not save jobs.yml: " + ex);
        }
    }

    private void loadJobs() {
        File f = new File(getDataFolder(), "jobs.yml");
        if (f.isFile() && getConfig().getBoolean("persistence.enabled", true)) {
            ConfigurationSection jobs = YamlConfiguration.loadConfiguration(f).getConfigurationSection("jobs");
            if (jobs != null) {
                for (String key : jobs.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(key);
                        BuildJob job = BuildJob.restore(this, id, jobs.getConfigurationSection(key));
                        Session s = session(id);
                        s.job = job;
                        s.blueprint = job.source();
                        s.rotation = job.rotation();
                        s.origin = job.origin();
                        s.chest = job.chestLocation();
                        job.resumeAfterRestore();
                        getLogger().info("Resumed the saved build of " + key + " (" + job.processed() + "/" + job.total() + ").");
                    } catch (IOException | RuntimeException ex) {
                        getLogger().warning("Could not resume saved build " + key + ": " + ex);
                    }
                }
            }
        }
        jobsLoaded = true;
    }
}
