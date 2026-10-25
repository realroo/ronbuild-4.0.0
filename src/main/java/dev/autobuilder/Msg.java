package dev.autobuilder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

final class Msg {
    private Msg() {}

    private static final Component PREFIX = Component.text("[RonAutoBuilder] ", NamedTextColor.GOLD);

    static void info(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY)));
    }

    static void error(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.RED)));
    }
}
