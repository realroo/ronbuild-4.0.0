package dev.autobuilder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two different interfaces: the green "Builder's Table" for survival players and the purple
 * "Control Panel" for admins. Both have a full-page schematic browser and a material checklist;
 * survival players also get the hire menu and the price confirmation.
 */
final class BuilderGui implements Listener {

    private enum Kind { HOME, SCHEMATICS, MATERIALS, HIRE, CONFIRM }

    private enum Action {
        INFO, SCHEMATICS, ORIGIN_HERE, ORIGIN_LOOK, ROTATE, PREVIEW, BUILDERS, SPEED, PATTERN, SUPPLY, WATER,
        AIR, CHEST, MATERIALS, BUILD, REBUILD, PAUSE, CANCEL, UNDO, CLOSE, SWITCH_VIEW, ADMIN_VIEW, RELOAD,
        PREV, NEXT, BACK, PICK, SUMMARY, HIRE, TIER_BUILDER, TIER_SPEED, MODE, CONFIRM_YES, CONFIRM_NO
    }

    private static Map.Entry<Integer, Action> slot(int slot, Action a) {
        return Map.entry(slot, a);
    }

    // slot = row * 9 + column (6 rows)
    private static final Map<Integer, Action> SURVIVAL_LAYOUT = Map.ofEntries(
            slot(4, Action.INFO),
            slot(10, Action.SCHEMATICS), slot(11, Action.ORIGIN_HERE), slot(12, Action.ORIGIN_LOOK),
            slot(13, Action.ROTATE), slot(14, Action.PREVIEW), slot(16, Action.MATERIALS),
            slot(19, Action.HIRE), slot(20, Action.PATTERN), slot(21, Action.WATER),
            slot(23, Action.SUPPLY), slot(24, Action.CHEST),
            slot(37, Action.BUILD), slot(38, Action.PAUSE), slot(39, Action.CANCEL),
            slot(40, Action.UNDO), slot(41, Action.REBUILD), slot(43, Action.CLOSE));

    private static final Map<Integer, Action> ADMIN_LAYOUT = Map.ofEntries(
            slot(4, Action.INFO),
            slot(10, Action.SCHEMATICS), slot(12, Action.ORIGIN_HERE), slot(13, Action.ORIGIN_LOOK),
            slot(14, Action.ROTATE), slot(16, Action.PREVIEW),
            slot(19, Action.BUILDERS), slot(20, Action.SPEED), slot(21, Action.PATTERN),
            slot(23, Action.SUPPLY), slot(24, Action.WATER), slot(25, Action.AIR),
            slot(28, Action.MATERIALS), slot(30, Action.SWITCH_VIEW), slot(32, Action.RELOAD),
            slot(38, Action.BUILD), slot(39, Action.PAUSE), slot(40, Action.CANCEL),
            slot(41, Action.UNDO), slot(42, Action.REBUILD), slot(49, Action.CLOSE));

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Kind kind;
        boolean survival;
        int page;
        final Map<Integer, Action> actions = new HashMap<>();
        final Map<Integer, String> picks = new HashMap<>();
        final Map<Integer, Integer> tiers = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final AutoBuilderPlugin plugin;

    BuilderGui(AutoBuilderPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player p) {
        Session s = plugin.session(p.getUniqueId());
        open(p, Kind.HOME, plugin.isSurvival(p, s), 0);
    }

    void openHire(Player p) {
        open(p, Kind.HIRE, true, 0);
    }

    void openConfirm(Player p) {
        open(p, Kind.CONFIRM, true, 0);
    }

    private void open(Player p, Kind kind, boolean survival, int page) {
        Holder h = new Holder();
        h.kind = kind;
        h.survival = survival;
        h.page = page;
        NamedTextColor accent = survival ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        String sub = switch (kind) {
            case HOME -> survival ? "Builder's Table" : "Control Panel";
            case SCHEMATICS -> "Schematics";
            case MATERIALS -> "Materials";
            case HIRE -> "Hire Builders";
            case CONFIRM -> "Confirm Build";
        };
        h.inventory = Bukkit.createInventory(h, 54,
                Component.text("RonAutoBuilder", accent).append(Component.text(" | " + sub, NamedTextColor.GRAY)));
        render(h, p, plugin.session(p.getUniqueId()));
        p.openInventory(h.inventory);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // ------------------------------------------------------------------ rendering

    private void render(Holder h, Player p, Session s) {
        h.inventory.clear();
        h.actions.clear();
        h.picks.clear();
        h.tiers.clear();
        switch (h.kind) {
            case HOME -> renderHome(h, p, s);
            case SCHEMATICS -> renderSchematics(h, s);
            case MATERIALS -> renderMaterials(h, p, s);
            case HIRE -> renderHire(h, p, s);
            case CONFIRM -> renderConfirm(h, s);
        }
    }

    private void fill(Inventory inv, boolean sv, int from, int to) {
        ItemStack pane = item(sv ? Material.LIME_STAINED_GLASS_PANE : Material.PURPLE_STAINED_GLASS_PANE,
                NamedTextColor.DARK_GRAY, " ");
        for (int i = from; i < to; i++) inv.setItem(i, pane);
    }

    private void renderHome(Holder h, Player p, Session s) {
        boolean sv = h.survival;
        fill(h.inventory, sv, 0, 54);
        for (Map.Entry<Integer, Action> e : (sv ? SURVIVAL_LAYOUT : ADMIN_LAYOUT).entrySet()) {
            ItemStack it = icon(e.getValue(), p, s, sv);
            if (it == null) continue;
            h.inventory.setItem(e.getKey(), it);
            h.actions.put(e.getKey(), e.getValue());
        }
        if (sv && plugin.isAdmin(p)) { // an admin testing the survival view can go back
            h.inventory.setItem(49, icon(Action.ADMIN_VIEW, p, s, true));
            h.actions.put(49, Action.ADMIN_VIEW);
        }
    }

    /** The full-page schematic browser: only schematics, page buttons, back and close. */
    private void renderSchematics(Holder h, Session s) {
        boolean sv = h.survival;
        NamedTextColor accent = sv ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        List<String> names = plugin.fileNames(sv);
        int per = 45;
        int pages = Math.max(1, (names.size() + per - 1) / per);
        h.page = Math.min(Math.max(h.page, 0), pages - 1);
        fill(h.inventory, sv, 45, 54);
        for (int i = 0; i < per; i++) {
            int idx = h.page * per + i;
            if (idx >= names.size()) break;
            String n = names.get(idx);
            boolean sel = s.blueprint != null && n.equals(s.blueprint.fileName());
            h.inventory.setItem(i, item(sel ? Material.ENCHANTED_BOOK : Material.PAPER, sel ? NamedTextColor.GOLD : accent,
                    (sel ? "> " : "") + n, sel ? "Currently selected" : "Click to load"));
            h.actions.put(i, Action.PICK);
            h.picks.put(i, n);
        }
        if (names.isEmpty()) {
            h.inventory.setItem(22, item(Material.BARRIER, NamedTextColor.RED, "No schematics found",
                    "Put .schem, .litematic or .nbt files in",
                    plugin.schematicDir(sv).getPath().replace('\\', '/') + "/"));
        }
        navigation(h, accent, pages);
    }

    private void renderMaterials(Holder h, Player p, Session s) {
        boolean sv = h.survival;
        NamedTextColor accent = sv ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        List<AutoBuilderPlugin.MatLine> lines = plugin.materialLines(p, s);
        int per = 45;
        int pages = Math.max(1, (lines.size() + per - 1) / per);
        h.page = Math.min(Math.max(h.page, 0), pages - 1);
        fill(h.inventory, sv, 45, 54);
        for (int i = 0; i < per; i++) {
            int idx = h.page * per + i;
            if (idx >= lines.size()) break;
            AutoBuilderPlugin.MatLine l = lines.get(idx);
            boolean ok = l.have() >= l.need();
            int stack = l.material().getMaxStackSize();
            int shown = (int) Math.max(1, Math.min(stack, l.need()));
            ItemStack it = new ItemStack(l.material(), shown);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(AutoBuilderPlugin.pretty(l.material()) + ": " + l.need(),
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(line("Needed: " + l.need() + (l.need() >= stack
                        ? " (" + (l.need() / stack) + " stacks + " + (l.need() % stack) + ")" : "")));
                lore.add(line("You have: " + l.have()));
                lore.add(Component.text(ok ? "Enough" : "Missing: " + (l.need() - l.have()),
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                it.setItemMeta(meta);
            }
            h.inventory.setItem(i, it);
        }
        boolean noItems = s.supply == BuildJob.Supply.FREE || s.supply == BuildJob.Supply.CONTRACT;
        h.inventory.setItem(47, item(Material.ENCHANTED_BOOK, accent, "Material checklist",
                s.blueprint == null ? "No schematic selected" : s.blueprint.name() + ": " + lines.size() + " block types",
                noItems ? "The builders bring the blocks - nothing is needed" : "Green = you have enough, red = missing",
                s.supply == BuildJob.Supply.CHEST ? "Counting your inventory + the linked chest" : "Counting your inventory"));
        h.actions.put(47, Action.SUMMARY);
        navigation(h, accent, pages);
    }

    private void renderHire(Holder h, Player p, Session s) {
        fill(h.inventory, true, 0, 54);
        Wallet w = plugin.wallet();
        FileConfiguration m = plugin.mode(true);
        int capB = m.getInt("max-builders", 4);
        double capS = m.getDouble("max-speed", 20);

        int slot = 10, n = 1;
        for (Map.Entry<Integer, Double> e : plugin.builderTiers().entrySet()) {
            if (slot > 16) break;
            boolean ok = plugin.tierAllowed(e.getKey(), e.getValue(), capB);
            h.inventory.setItem(slot, tierItem(Material.PLAYER_HEAD, n, "Builder x" + e.getKey(), e.getValue(),
                    ok, s.builders == e.getKey(), w));
            if (ok) {
                h.actions.put(slot, Action.TIER_BUILDER);
                h.tiers.put(slot, e.getKey());
            }
            slot++;
            n++;
        }
        slot = 28;
        n = 1;
        for (Map.Entry<Integer, Double> e : plugin.speedTiers().entrySet()) {
            if (slot > 34) break;
            boolean ok = plugin.tierAllowed(e.getKey(), e.getValue(), capS);
            h.inventory.setItem(slot, tierItem(Material.CLOCK, n, "Speed " + e.getKey() + " blocks/s", e.getValue(),
                    ok, (int) s.speed == e.getKey(), w));
            if (ok) {
                h.actions.put(slot, Action.TIER_SPEED);
                h.tiers.put(slot, e.getKey());
            }
            slot++;
            n++;
        }
        String fee = plugin.hireSummary(p, s);
        h.inventory.setItem(4, item(Material.NETHER_STAR, NamedTextColor.GREEN, "Hire builders",
                "Selected: " + s.builders + " builder(s), speed " + (int) s.speed + " blocks/s",
                fee == null ? "No hire fee" : "Hire fee for your next build: " + fee,
                "Prices are charged once per build"));
        h.inventory.setItem(22, item(Material.OAK_SIGN, NamedTextColor.GRAY, "Builders (top row)  |  Speed (bottom row)"));
        h.inventory.setItem(40, item(Material.COMPARATOR, NamedTextColor.GREEN, "Work mode: " + s.mode,
                "Left: next   Right: previous", "SHARED: one shared queue", "OPPOSITE: builders start from both ends",
                "SPLIT: each builder gets a section"));
        h.actions.put(40, Action.MODE);
        h.inventory.setItem(49, item(Material.BARRIER, NamedTextColor.RED, "Back"));
        h.actions.put(49, Action.BACK);
    }

    private void renderConfirm(Holder h, Session s) {
        fill(h.inventory, true, 0, 54);
        BuildJob.Quote q = s.pendingQuote;
        Wallet w = plugin.wallet();
        if (q == null || w == null) {
            h.inventory.setItem(22, item(Material.BARRIER, NamedTextColor.RED, "Nothing to confirm"));
            h.inventory.setItem(49, item(Material.BARRIER, NamedTextColor.RED, "Close"));
            h.actions.put(49, Action.CLOSE);
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("Schematic: " + (s.blueprint == null ? "?" : s.blueprint.name()));
        lore.add("Blocks to place: " + q.blocks());
        if (q.hire() > 0) lore.add("Hiring: " + w.format(q.hire()));
        if (q.materials() > 0) lore.add("Materials (shop prices): " + w.format(q.materials()));
        if (q.labor() > 0) lore.add("Labor: " + w.format(q.labor()));
        lore.add(" ");
        lore.add("Pay now: " + w.format(q.upfront()));
        if (q.payAsYouGo() > 0) lore.add("Pay while building: about " + w.format(q.payAsYouGo()));
        lore.add("Unused money is refunded if you cancel.");
        h.inventory.setItem(13, item(Material.NETHER_STAR, NamedTextColor.GREEN, "Your build", lore.toArray(new String[0])));

        String yes = q.upfront() > 0 ? "CONFIRM - pay " + w.format(q.upfront()) : "CONFIRM - start building";
        h.inventory.setItem(30, item(Material.LIME_CONCRETE, NamedTextColor.GREEN, yes,
                q.upfront() > 0 ? "The amount is taken before the first block" : "Money is taken while the builders work"));
        h.actions.put(30, Action.CONFIRM_YES);
        h.inventory.setItem(32, item(Material.RED_CONCRETE, NamedTextColor.RED, "CANCEL", "Nothing is charged"));
        h.actions.put(32, Action.CONFIRM_NO);
    }

    private void navigation(Holder h, NamedTextColor accent, int pages) {
        if (h.page > 0) {
            h.inventory.setItem(45, item(Material.ARROW, accent, "Previous page"));
            h.actions.put(45, Action.PREV);
        }
        h.inventory.setItem(48, item(Material.SPECTRAL_ARROW, NamedTextColor.YELLOW, "Back"));
        h.actions.put(48, Action.BACK);
        h.inventory.setItem(49, item(Material.PAPER, accent, "Page " + (h.page + 1) + " / " + pages));
        h.inventory.setItem(50, item(Material.BARRIER, NamedTextColor.RED, "Close"));
        h.actions.put(50, Action.CLOSE);
        if (h.page < pages - 1) {
            h.inventory.setItem(53, item(Material.ARROW, accent, "Next page"));
            h.actions.put(53, Action.NEXT);
        }
    }

    private static String originText(Session s) {
        return s.origin == null ? "not set"
                : s.origin.getBlockX() + ", " + s.origin.getBlockY() + ", " + s.origin.getBlockZ();
    }

    private ItemStack info(Player p, Session s, boolean sv) {
        List<String> l = new ArrayList<>();
        if (s.blueprint == null) {
            l.add("No schematic selected yet");
        } else {
            l.add("Schematic: " + s.blueprint.fileName());
            l.add("Size: " + s.blueprint.sizeX(s.rotation) + " x " + s.blueprint.height() + " x "
                    + s.blueprint.sizeZ(s.rotation) + "  |  " + s.blueprint.blockCount() + " blocks");
        }
        l.add("Origin: " + originText(s));
        l.add("Rotation: " + s.rotation + " deg");
        if (s.job != null) l.add("Build: " + s.job.state() + " " + s.job.processed() + "/" + s.job.total());
        String fee = plugin.hireSummary(p, s);
        if (fee != null) l.add("Hire fee: " + fee);
        return item(Material.NETHER_STAR, sv ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE,
                sv ? "Builder's Table" : "Control Panel", l.toArray(new String[0]));
    }

    private ItemStack icon(Action a, Player p, Session s, boolean sv) {
        NamedTextColor c = sv ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE;
        int max = plugin.maxBuilders(sv);
        return switch (a) {
            case INFO -> info(p, s, sv);
            case SCHEMATICS -> item(Material.BOOKSHELF, c, "Choose schematic",
                    plugin.fileNames(sv).size() + " file(s) found", "Click to browse");
            case ORIGIN_HERE -> item(Material.GRASS_BLOCK, c, "Set origin here",
                    "Your feet become the lowest corner", "Now: " + originText(s));
            case ORIGIN_LOOK -> item(Material.SPYGLASS, c, "Set origin on the block you look at",
                    "Now: " + originText(s));
            case ROTATE -> item(Material.COMPASS, c, "Rotate: " + s.rotation + " deg",
                    "Left: +90   Right: -90", "Redraws an active preview");
            case PREVIEW -> item(Material.ENDER_EYE, c, "Preview: " + s.preview,
                    "Left: show   Right: switch OUTLINE / GHOST", "Shift: hide", "Ghost blocks are only visible to you");
            case BUILDERS -> item(Material.PLAYER_HEAD, c, "Builders: " + s.builders + " (max " + max + ")",
                    "Left: +1   Right: -1", "Mode: " + s.mode + "   (shift-click to change)",
                    "SHARED: one shared queue", "OPPOSITE: builders start from both ends", "SPLIT: each builder gets a section");
            case SPEED -> item(Material.CLOCK, c, "Speed: " + (int) s.speed + " blocks/s per builder",
                    "Max: " + (int) plugin.maxSpeed(sv), "Left: +5   Right: -5", "Shift: change by 1");
            case HIRE -> {
                String fee = plugin.hireSummary(p, s);
                yield item(Material.PLAYER_HEAD, c, "Hire builders",
                        "Builders: " + s.builders + "   Speed: " + (int) s.speed + " blocks/s",
                        fee == null ? "No hire fee" : "Hire fee for the next build: " + fee,
                        "Click to open the hire menu");
            }
            case PATTERN -> item(Material.COMPARATOR, c, "Pattern: " + s.pattern,
                    "Left: next   Right: previous", "UP, DOWN, RANDOM, OUTWARD",
                    "LEFT, RIGHT (as you face)", "NORTH, SOUTH, EAST, WEST");
            case SUPPLY -> item(Material.HOPPER, c, "Materials: " + s.supply,
                    s.supply == BuildJob.Supply.FREE ? "No items needed (admin)"
                            : s.supply == BuildJob.Supply.INVENTORY ? "Taken from your inventory"
                            : s.supply == BuildJob.Supply.CHEST ? "Builders fetch from the linked chest"
                            : "Builders bring the blocks, you pay the shop price", "Click to switch");
            case WATER -> item(Material.WATER_BUCKET, c, "Ignore water: " + (s.ignoreWater ? "ON" : "OFF"));
            case AIR -> item(Material.GLASS, c, "Ignore air: " + (s.ignoreAir ? "ON" : "OFF"),
                    "ON: do not clear existing blocks");
            case CHEST -> item(Material.BARREL, c, "Link supply chest", "Look at a chest or barrel, then click",
                    "Linked: " + (s.chest == null ? "none"
                            : s.chest.getBlockX() + ", " + s.chest.getBlockY() + ", " + s.chest.getBlockZ()));
            case MATERIALS -> item(Material.CRAFTING_TABLE, c, "Material checklist",
                    "Shows every block you need with icons", "Green = enough, red = missing");
            case BUILD -> item(Material.LIME_CONCRETE, NamedTextColor.GREEN, "BUILD",
                    "Schematic: " + (s.blueprint == null ? "none selected" : s.blueprint.name()));
            case REBUILD -> item(Material.ANVIL, NamedTextColor.GOLD, "Rebuild / repair",
                    "Places only missing or wrong blocks");
            case PAUSE -> item(Material.YELLOW_CONCRETE, NamedTextColor.YELLOW, "Pause / Resume",
                    s.job == null ? "no build yet" : s.job.state() + " " + s.job.processed() + "/" + s.job.total());
            case CANCEL -> item(Material.RED_CONCRETE, NamedTextColor.RED, "Cancel build",
                    "Unused prepaid money is refunded");
            case UNDO -> item(Material.TNT, NamedTextColor.RED, "Undo last build");
            case CLOSE -> item(Material.BARRIER, NamedTextColor.RED, "Close");
            case SWITCH_VIEW -> item(Material.EMERALD, NamedTextColor.GREEN, "Switch to survival view",
                    "See and test what players see");
            case ADMIN_VIEW -> item(Material.COMMAND_BLOCK, NamedTextColor.LIGHT_PURPLE, "Back to admin view");
            case RELOAD -> item(Material.REDSTONE, NamedTextColor.RED, "Reload config", "All configuration files");
            default -> null;
        };
    }

    private ItemStack tierItem(Material m, int amount, String title, double price, boolean allowed,
                               boolean selected, Wallet w) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            NamedTextColor color = !allowed ? NamedTextColor.RED : selected ? NamedTextColor.GOLD : NamedTextColor.GREEN;
            meta.displayName(Component.text((selected ? "> " : "") + title, color).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line(price <= 0 ? "Price: free"
                    : "Price: " + (w == null ? String.valueOf(price) : w.format(price)) + " per build"));
            lore.add(line(!allowed ? "Not available" : selected ? "Selected" : "Click to select"));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, NamedTextColor color, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> l = new ArrayList<>();
                for (String s : lore) l.add(line(s));
                meta.lore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static <T> T cycle(T[] values, int index, boolean back) {
        int n = values.length;
        return values[(index + (back ? n - 1 : 1)) % n];
    }

    private BuildJob.Supply nextSupply(BuildJob.Supply cur, boolean survival, boolean back) {
        List<BuildJob.Supply> opts = new ArrayList<>();
        if (!survival) opts.add(BuildJob.Supply.FREE);
        opts.add(BuildJob.Supply.INVENTORY);
        opts.add(BuildJob.Supply.CHEST);
        if (survival && plugin.contractAllowed()) opts.add(BuildJob.Supply.CONTRACT);
        int i = Math.max(0, opts.indexOf(cur));
        return cycle(opts.toArray(new BuildJob.Supply[0]), i, back);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        if (!h.survival && !plugin.isAdmin(p)) {
            p.closeInventory();
            return;
        }
        Action a = h.actions.get(e.getSlot());
        if (a == null) return;
        Session s = plugin.session(p.getUniqueId());
        if (handle(a, h, p, s, e.isRightClick(), e.isShiftClick(), e.getSlot())) render(h, p, s);
    }

    /** Returns true when the menu should be redrawn. */
    private boolean handle(Action a, Holder h, Player p, Session s, boolean right, boolean shift, int slot) {
        boolean sv = h.survival;
        switch (a) {
            case SCHEMATICS -> {
                later(() -> open(p, Kind.SCHEMATICS, sv, 0));
                return false;
            }
            case MATERIALS -> {
                later(() -> open(p, Kind.MATERIALS, sv, 0));
                return false;
            }
            case HIRE -> {
                later(() -> open(p, Kind.HIRE, true, 0));
                return false;
            }
            case BACK -> {
                later(() -> open(p, Kind.HOME, sv, 0));
                return false;
            }
            case PICK -> {
                String name = h.picks.get(slot);
                if (name != null && plugin.load(p, s, name)) {
                    plugin.preview().refresh(p, s);
                    later(() -> open(p, Kind.HOME, sv, 0));
                    return false;
                }
                return true;
            }
            case TIER_BUILDER -> {
                Integer tier = h.tiers.get(slot);
                if (tier != null) plugin.selectBuilders(p, s, tier);
            }
            case TIER_SPEED -> {
                Integer tier = h.tiers.get(slot);
                if (tier != null) plugin.selectSpeed(p, s, tier);
            }
            case MODE -> s.mode = cycle(BuildJob.Mode.values(), s.mode.ordinal(), right);
            case CONFIRM_YES -> {
                p.closeInventory();
                plugin.confirmBuild(p, s);
                return false;
            }
            case CONFIRM_NO -> {
                p.closeInventory();
                plugin.denyBuild(p, s);
                return false;
            }
            case PREV -> h.page--;
            case NEXT -> h.page++;
            case ORIGIN_HERE -> plugin.setOriginHere(p, s);
            case ORIGIN_LOOK -> plugin.setOriginLook(p, s);
            case ROTATE -> {
                s.rotation = (s.rotation + (right ? 270 : 90)) % 360;
                plugin.preview().refresh(p, s);
            }
            case PREVIEW -> {
                if (shift) {
                    plugin.preview().clear(p);
                } else if (right) {
                    s.preview = s.preview == Preview.Type.OUTLINE ? Preview.Type.GHOST : Preview.Type.OUTLINE;
                } else {
                    plugin.preview().show(p, s);
                }
            }
            case BUILDERS -> {
                if (shift) s.mode = cycle(BuildJob.Mode.values(), s.mode.ordinal(), right);
                else s.builders = clampI(s.builders + (right ? -1 : 1), 1, plugin.maxBuilders(sv));
            }
            case SPEED -> s.speed = clampD(s.speed + (shift ? 1 : 5) * (right ? -1 : 1), 1.0, plugin.maxSpeed(sv));
            case PATTERN -> s.pattern = cycle(BuildJob.Pattern.values(), s.pattern.ordinal(), right);
            case SUPPLY -> s.supply = nextSupply(s.supply, sv, right);
            case WATER -> s.ignoreWater = !s.ignoreWater;
            case AIR -> {
                if (!sv) s.ignoreAir = !s.ignoreAir;
            }
            case CHEST -> plugin.linkChest(p, s);
            case BUILD, REBUILD -> {
                p.closeInventory();
                plugin.startBuild(p, s);
                return false;
            }
            case PAUSE -> {
                if (s.job != null) {
                    if (s.job.state() == BuildJob.State.PAUSED) s.job.resume();
                    else s.job.pause();
                }
            }
            case CANCEL -> {
                if (s.job != null) s.job.cancel();
            }
            case UNDO -> {
                if (s.job != null) Msg.info(p, "Undid " + s.job.undo() + " blocks.");
            }
            case SWITCH_VIEW -> {
                s.adminView = false;
                later(() -> open(p, Kind.HOME, true, 0));
                return false;
            }
            case ADMIN_VIEW -> {
                if (!plugin.isAdmin(p)) return false;
                s.adminView = true;
                later(() -> open(p, Kind.HOME, false, 0));
                return false;
            }
            case RELOAD -> {
                plugin.reload();
                Msg.info(p, "All configuration files reloaded.");
            }
            case CLOSE -> {
                p.closeInventory();
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
