package dev.autobuilder;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BuildCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> ADMIN_ONLY = Set.of("admin", "pos1", "pos2", "save", "skin", "reload", "npc", "economy");

    private final AutoBuilderPlugin plugin;

    BuildCommand(AutoBuilderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.error(sender, "Players only.");
            return true;
        }
        boolean admin = plugin.isAdmin(p);
        if (!admin && !p.hasPermission("ronautobuilder.survival")) {
            Msg.error(p, "You don't have permission.");
            return true;
        }
        Session s = plugin.session(p.getUniqueId());
        if (args.length == 0) {
            plugin.gui().open(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_ONLY.contains(sub) && !admin) {
            Msg.error(p, "That command is for admins only.");
            return true;
        }
        switch (sub) {
            case "admin" -> {
                s.adminView = true;
                plugin.gui().open(p);
            }
            case "survival" -> {
                if (admin) s.adminView = false;
                plugin.gui().open(p);
            }
            case "gui" -> plugin.gui().open(p);
            case "list" -> list(p, plugin.isSurvival(p, s));
            case "load" -> {
                if (args.length < 2) {
                    Msg.error(p, "Usage: /ab load <name>");
                } else if (plugin.load(p, s, args[1])) {
                    plugin.preview().refresh(p, s);
                }
            }
            case "origin" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("look")) plugin.setOriginLook(p, s);
                else plugin.setOriginHere(p, s);
            }
            case "rotate" -> rotate(p, s, args);
            case "preview" -> {
                if (args.length > 1) {
                    String a = args[1].toLowerCase(Locale.ROOT);
                    if (a.equals("off")) {
                        plugin.preview().clear(p);
                        Msg.info(p, "Preview hidden.");
                        return true;
                    }
                    if (a.equals("ghost")) s.preview = Preview.Type.GHOST;
                    else if (a.equals("outline") || a.equals("particles")) s.preview = Preview.Type.OUTLINE;
                    else {
                        Msg.error(p, "Use: /ab preview [outline|ghost|off]");
                        return true;
                    }
                }
                plugin.preview().show(p, s);
            }
            case "build" -> build(p, s, args);
            case "rebuild" -> plugin.startBuild(p, s);
            case "pause" -> withJob(p, s, () -> { s.job.pause(); Msg.info(p, "Paused."); });
            case "resume" -> withJob(p, s, () -> { s.job.resume(); Msg.info(p, "Resumed."); });
            case "cancel" -> withJob(p, s, () -> { s.job.cancel(); Msg.info(p, "Cancelled. /ab undo reverts what was built."); });
            case "status" -> withJob(p, s, () -> Msg.info(p, s.job.state() + ": " + s.job.processed() + "/"
                    + s.job.total() + " processed, " + s.job.placed() + " placed."));
            case "undo" -> withJob(p, s, () -> {
                int n = s.job.undo();
                Msg.info(p, "Undid " + n + " blocks." + (s.job.undoTruncated() ? " (history was limited by build.max-undo)" : ""));
            });
            case "materials", "listmaterials" -> {
                int page = 0;
                if (args.length > 1) {
                    try {
                        page = Integer.parseInt(args[1]) - 1;
                    } catch (NumberFormatException ex) {
                        page = 0;
                    }
                }
                plugin.sendMaterials(p, s, page);
            }
            case "supply" -> supply(p, s, args);
            case "chest" -> plugin.linkChest(p, s);
            case "hire" -> hire(p, s, args);
            case "confirm" -> plugin.confirmBuild(p, s);
            case "deny" -> plugin.denyBuild(p, s);
            case "economy" -> economy(p);
            case "pos1" -> { s.pos1 = p.getLocation().getBlock().getLocation(); Msg.info(p, "Position 1 set."); }
            case "pos2" -> { s.pos2 = p.getLocation().getBlock().getLocation(); Msg.info(p, "Position 2 set."); }
            case "save" -> save(p, s, args);
            case "skin" -> skin(p, args);
            case "reload" -> {
                plugin.reload();
                Msg.info(p, "config.yml, survival.yml and admin.yml reloaded.");
            }
            case "npc" -> npc(p, args);
            default -> help(p, admin);
        }
        return true;
    }

    private void help(Player p, boolean admin) {
        Msg.info(p, "/ab opens the menu. Commands: list | load <name> | origin [look] | rotate [0|90|180|270]");
        Msg.info(p, "preview [outline|ghost|off] | build [builders=N] [speed=N] [pattern=P] [mode=M] [ignorewater]");
        Msg.info(p, "rebuild | pause | resume | cancel | status | undo | materials [page] | supply <inventory|chest|contract> | chest");
        Msg.info(p, "hire [builders <n> | speed <n>] | confirm | deny");
        Msg.info(p, "Patterns: up down random outward left right north south east west. Modes: shared opposite split.");
        if (admin) {
            Msg.info(p, "Admin: admin | survival | supply free | build ignoreair | pos1 | pos2 | save <name> [players] | skin <player|reset> | reload | economy");
            Msg.info(p, "NPCs: npc create|remove|list|move|tp|skin|command ...");
        }
    }

    private void list(Player p, boolean survival) {
        List<String> names = plugin.fileNames(survival);
        if (names.isEmpty()) {
            Msg.info(p, "No schematics found. Put .schem, .litematic or .nbt files in "
                    + plugin.schematicDir(survival).getPath().replace('\\', '/') + "/");
        } else {
            Msg.info(p, "Schematics: " + String.join(", ", names));
        }
    }

    private void rotate(Player p, Session s, String[] args) {
        int deg = (s.rotation + 90) % 360;
        if (args.length > 1) {
            try {
                deg = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                deg = -1;
            }
            if (deg != 0 && deg != 90 && deg != 180 && deg != 270) {
                Msg.error(p, "Rotation must be 0, 90, 180 or 270.");
                return;
            }
        }
        s.rotation = deg;
        Msg.info(p, "Rotation: " + deg + " degrees (clockwise, around the origin corner).");
        plugin.preview().refresh(p, s);
    }

    private void supply(Player p, Session s, String[] args) {
        boolean freeAllowed = !plugin.isSurvival(p, s);
        if (args.length < 2) {
            Msg.info(p, "Materials come from: " + s.supply.name().toLowerCase(Locale.ROOT)
                    + ". Use /ab supply inventory | chest | contract" + (freeAllowed ? " | free" : ""));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "free", "true" -> {
                if (!freeAllowed) {
                    Msg.error(p, "Free materials are only for admins.");
                } else {
                    s.supply = BuildJob.Supply.FREE;
                    Msg.info(p, "Builders now use free materials.");
                }
            }
            case "inventory", "false" -> {
                s.supply = BuildJob.Supply.INVENTORY;
                Msg.info(p, "Builders now take materials from your inventory.");
            }
            case "chest" -> {
                s.supply = BuildJob.Supply.CHEST;
                if (s.chest == null) Msg.info(p, "Now look at a chest or barrel near the build and run /ab chest to link it.");
                else Msg.info(p, "Builders will fetch materials from the linked chest.");
            }
            case "contract" -> {
                if (freeAllowed) {
                    Msg.error(p, "Contract builds are for survival players. Use /ab survival to test them.");
                } else if (!plugin.contractAllowed()) {
                    Msg.error(p, "Contract builds are not available on this server.");
                } else {
                    s.supply = BuildJob.Supply.CONTRACT;
                    Msg.info(p, "The builders will bring the blocks; you pay the shop price when you start the build.");
                }
            }
            default -> Msg.error(p, "Use: /ab supply inventory | chest | contract" + (freeAllowed ? " | free" : ""));
        }
    }

    private void withJob(Player p, Session s, Runnable action) {
        if (s.job == null) {
            Msg.error(p, "No build yet.");
            return;
        }
        action.run();
    }

    private void build(Player p, Session s, String[] args) {
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            try {
                if (a.equals("ignoreair")) s.ignoreAir = true;
                else if (a.equals("ignorewater")) s.ignoreWater = true;
                else if (a.startsWith("builders=")) s.builders = Integer.parseInt(a.substring(9));
                else if (a.startsWith("speed=")) s.speed = Double.parseDouble(a.substring(6));
                else if (a.startsWith("pattern=")) s.pattern = BuildJob.Pattern.valueOf(a.substring(8).toUpperCase(Locale.ROOT));
                else if (a.startsWith("mode=")) s.mode = BuildJob.Mode.valueOf(a.substring(5).toUpperCase(Locale.ROOT));
                else {
                    Msg.error(p, "Unknown option: " + args[i]);
                    return;
                }
            } catch (IllegalArgumentException ex) { // also covers NumberFormatException
                Msg.error(p, "Bad value in: " + args[i]);
                return;
            }
        }
        plugin.startBuild(p, s);
    }

    private void skin(Player p, String[] args) {
        FileConfiguration cfg = plugin.getConfig();
        if (args.length < 2) {
            String texture = cfg.getString("builder.skin.texture", "");
            String name = cfg.getString("builder.skin.name", "");
            String current = !texture.isBlank() ? "custom texture from config.yml" : name.isBlank() ? "default" : name;
            Msg.info(p, "Builder skin: " + current + ".");
            Msg.info(p, "Use /ab skin <minecraft username> or /ab skin reset. For an uploaded PNG, see config.yml.");
            return;
        }
        if (args[1].equalsIgnoreCase("reset")) {
            cfg.set("builder.skin.name", "");
            cfg.set("builder.skin.texture", "");
            cfg.set("builder.skin.signature", "");
            plugin.saveConfig();
            Msg.info(p, "Builder skin reset.");
            return;
        }
        if (!args[1].matches("[A-Za-z0-9_]{1,16}")) {
            Msg.error(p, "That is not a valid Minecraft username.");
            return;
        }
        cfg.set("builder.skin.name", args[1]);
        cfg.set("builder.skin.texture", "");
        cfg.set("builder.skin.signature", "");
        plugin.saveConfig();
        Msg.info(p, "Builders will use the skin of " + args[1] + " from the next build.");
    }

    private void save(Player p, Session s, String[] args) {
        if (args.length < 2) {
            Msg.error(p, "Usage: /ab save <name> [players]");
            return;
        }
        if (s.pos1 == null || s.pos2 == null) {
            Msg.error(p, "Set /ab pos1 and /ab pos2 first.");
            return;
        }
        if (!s.pos1.getWorld().equals(s.pos2.getWorld())) {
            Msg.error(p, "Both positions must be in the same world.");
            return;
        }
        String name = args[1];
        if (!AutoBuilderPlugin.safeName(name)) {
            Msg.error(p, "Invalid name.");
            return;
        }

        World w = s.pos1.getWorld();
        int minX = Math.min(s.pos1.getBlockX(), s.pos2.getBlockX());
        int minY = Math.min(s.pos1.getBlockY(), s.pos2.getBlockY());
        int minZ = Math.min(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        int maxX = Math.max(s.pos1.getBlockX(), s.pos2.getBlockX());
        int maxY = Math.max(s.pos1.getBlockY(), s.pos2.getBlockY());
        int maxZ = Math.max(s.pos1.getBlockZ(), s.pos2.getBlockZ());

        try {
            StructureManager sm = Bukkit.getStructureManager();
            Structure st = sm.createStructure();
            st.fill(new Location(w, minX, minY, minZ),
                    new BlockVector(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1), false);
            boolean forPlayers = args.length > 2 && args[2].equalsIgnoreCase("players");
            File out = new File(plugin.schematicDir(forPlayers), name + ".nbt");
            sm.saveStructure(out, st);
            Msg.info(p, "Saved " + out.getName() + " in the " + (forPlayers ? "players" : "admin") + " folder (includes chest contents etc.).");
        } catch (IOException | RuntimeException ex) {
            Msg.error(p, "Save failed: " + ex.getMessage());
        }
    }

    private void hire(Player p, Session s, String[] args) {
        if (args.length < 2) {
            plugin.gui().openHire(p);
            return;
        }
        if (args.length < 3) {
            Msg.error(p, "Usage: /ab hire builders <n> | /ab hire speed <n>");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            Msg.error(p, "That is not a number.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "builders" -> plugin.selectBuilders(p, s, n);
            case "speed" -> plugin.selectSpeed(p, s, n);
            default -> Msg.error(p, "Usage: /ab hire builders <n> | /ab hire speed <n>");
        }
    }

    private void economy(Player p) {
        Wallet w = plugin.wallet();
        Msg.info(p, w == null ? "Economy is off (economy.yml) or no economy plugin was found."
                : "Economy active: " + w.name());
    }

    private void npc(Player p, String[] args) {
        NpcManager m = plugin.npcs();
        if (args.length < 2) {
            Msg.info(p, "/ab npc create <name> | remove <name> | list | move <name> | tp <name> | skin <name> <username|reset>");
            Msg.info(p, "/ab npc command add <npc> <player|console|say> <text...> | command list <npc> | command clear <npc>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 3) Msg.error(p, "Usage: /ab npc create <name>");
                else m.create(p, args[2]);
            }
            case "remove" -> {
                if (args.length < 3) Msg.error(p, "Usage: /ab npc remove <name>");
                else m.remove(p, args[2]);
            }
            case "list" -> {
                List<String> names = m.names();
                Msg.info(p, names.isEmpty() ? "No NPCs yet. Use /ab npc create <name>." : "NPCs: " + String.join(", ", names));
            }
            case "move" -> {
                if (args.length < 3) Msg.error(p, "Usage: /ab npc move <name>");
                else m.moveHere(p, args[2]);
            }
            case "tp" -> {
                if (args.length < 3) Msg.error(p, "Usage: /ab npc tp <name>");
                else m.tpTo(p, args[2]);
            }
            case "skin" -> {
                if (args.length < 4) Msg.error(p, "Usage: /ab npc skin <name> <username|reset>");
                else m.setSkin(p, args[2], args[3]);
            }
            case "command" -> command(p, m, args);
            default -> Msg.error(p, "Unknown NPC command. Type /ab npc for help.");
        }
    }

    private void command(Player p, NpcManager m, String[] args) {
        if (args.length < 4) {
            Msg.error(p, "Usage: /ab npc command add|list|clear <npc> ...");
            return;
        }
        String name = args[3];
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 6) {
                    Msg.error(p, "Usage: /ab npc command add <npc> <player|console|say> <text...>");
                    return;
                }
                String type = args[4].toLowerCase(Locale.ROOT);
                if (!type.equals("player") && !type.equals("console") && !type.equals("say")) {
                    Msg.error(p, "Type must be player (clicker runs it), console (server runs it) or say (message).");
                    return;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                if (text.startsWith("/")) text = text.substring(1);
                m.addCommand(p, name, type + ":" + text);
            }
            case "list" -> m.sendCommands(p, name);
            case "clear" -> m.clearCommands(p, name);
            default -> Msg.error(p, "Use add, list or clear.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        boolean admin = sender instanceof Player pl && plugin.isAdmin(pl);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("gui", "help", "list", "load", "origin", "rotate", "preview",
                    "build", "rebuild", "pause", "resume", "cancel", "status", "undo", "materials", "supply", "chest",
                    "survival", "hire", "confirm", "deny"));
            if (admin) subs.addAll(List.of("admin", "pos1", "pos2", "save", "skin", "reload", "npc", "economy"));
            return filter(subs, args[0]);
        }
        String cur = args[args.length - 1];
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "load": if (args.length == 2) return filter(plugin.fileNames(viewSurvival(sender)), cur); break;
            case "origin": if (args.length == 2) return filter(List.of("look"), cur); break;
            case "rotate": if (args.length == 2) return filter(List.of("0", "90", "180", "270"), cur); break;
            case "preview": if (args.length == 2) return filter(List.of("outline", "ghost", "off"), cur); break;
            case "supply":
                if (args.length == 2) return filter(admin ? List.of("inventory", "chest", "free") : List.of("inventory", "chest", "contract"), cur);
                break;
            case "hire": if (args.length == 2) return filter(List.of("builders", "speed"), cur); break;
            case "save": if (admin && args.length == 3) return filter(List.of("players"), cur); break;
            case "skin": if (admin && args.length == 2) return filter(List.of("reset"), cur); break;
            case "build": {
                List<String> opts = new ArrayList<>(List.of("ignorewater", "builders=", "speed=",
                        "mode=shared", "mode=opposite", "mode=split",
                        "pattern=up", "pattern=down", "pattern=random", "pattern=outward",
                        "pattern=left", "pattern=right", "pattern=north", "pattern=south", "pattern=east", "pattern=west"));
                if (admin) opts.add("ignoreair");
                return filter(opts, cur);
            }
            case "npc":
                if (!admin) break;
                if (args.length == 2) return filter(List.of("create", "remove", "list", "move", "tp", "skin", "command"), cur);
                String act = args[1].toLowerCase(Locale.ROOT);
                if (args.length == 3 && (act.equals("remove") || act.equals("move") || act.equals("tp") || act.equals("skin"))) {
                    return filter(plugin.npcs().names(), cur);
                }
                if (act.equals("skin") && args.length == 4) return filter(List.of("reset"), cur);
                if (act.equals("command")) {
                    if (args.length == 3) return filter(List.of("add", "list", "clear"), cur);
                    if (args.length == 4) return filter(plugin.npcs().names(), cur);
                    if (args.length == 5 && args[2].equalsIgnoreCase("add")) return filter(List.of("player", "console", "say"), cur);
                }
                break;
            default: break;
        }
        return List.of();
    }

    private boolean viewSurvival(CommandSender sender) {
        return !(sender instanceof Player pl) || plugin.isSurvival(pl, plugin.session(pl.getUniqueId()));
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
