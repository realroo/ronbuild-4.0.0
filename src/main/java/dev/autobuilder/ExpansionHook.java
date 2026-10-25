package dev.autobuilder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI placeholders (identifier "autobuilder"):
 * %autobuilder_status%  %autobuilder_progress%  %autobuilder_active_builders%
 * %autobuilder_placed%  %autobuilder_total%  %autobuilder_schematic%
 */
final class ExpansionHook extends PlaceholderExpansion {

    private final AutoBuilderPlugin plugin;

    ExpansionHook(AutoBuilderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "autobuilder";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Ron";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        Session s = plugin.sessionIfPresent(player.getUniqueId());
        BuildJob job = s == null ? null : s.job;
        boolean active = job != null && job.isActive();
        switch (params.toLowerCase(Locale.ROOT)) {
            case "status":
                if (job == null) return "idle";
                return switch (job.state()) {
                    case RUNNING -> "building";
                    case PAUSED -> "paused";
                    case DONE -> "finished";
                    case CANCELLED -> "cancelled";
                };
            case "progress":
                if (job == null) return "0";
                return job.total() == 0 ? "100" : String.valueOf((int) (100L * job.processed() / job.total()));
            case "active_builders":
                return active ? String.valueOf(job.builders().size()) : "0";
            case "placed":
                return job == null ? "0" : String.valueOf(job.placed());
            case "total":
                return job == null ? "0" : String.valueOf(job.total());
            case "schematic":
                return job == null ? "" : job.source().name();
            default:
                return null;
        }
    }
}
