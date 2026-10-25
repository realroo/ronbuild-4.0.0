package dev.autobuilder;

import org.bukkit.OfflinePlayer;

/** Where the money comes from: an economy plugin (through Vault) or an item such as diamonds. */
interface Wallet {
    /** Name shown in the console, for example the economy plugin that was found. */
    String name();

    /** What players pay with, for example "money" or "Diamond". */
    String currencyName();

    boolean withdraw(OfflinePlayer player, double amount);

    void deposit(OfflinePlayer player, double amount);

    String format(double amount);

    /** Rounds a price up to something this currency can charge. */
    double round(double amount);

    /** Rounds a refund down to something this currency can pay back. */
    double roundDown(double amount);
}
