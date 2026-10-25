package dev.autobuilder;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Payment with an item (for example diamonds) taken from the player's inventory. */
final class ItemWallet implements Wallet {

    private final Material item;

    ItemWallet(Material item) {
        this.item = item;
    }

    @Override
    public String name() {
        return "Items -> " + AutoBuilderPlugin.pretty(item);
    }

    @Override
    public String currencyName() {
        return AutoBuilderPlugin.pretty(item);
    }

    private long count(Player p) {
        long n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == item) n += it.getAmount();
        }
        return n;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Player p = player.getPlayer();
        if (p == null) return false;
        int n = (int) Math.ceil(amount - 1e-9);
        if (n <= 0) return true;
        if (count(p) < n) return false;
        p.getInventory().removeItem(new ItemStack(item, n));
        return true;
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        Player p = player.getPlayer();
        int n = (int) Math.floor(amount + 1e-9);
        if (p == null || n <= 0) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(item, n));
        for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    @Override
    public String format(double amount) {
        return ((long) Math.ceil(amount - 1e-9)) + " " + AutoBuilderPlugin.pretty(item);
    }

    @Override
    public double round(double amount) {
        return Math.ceil(amount - 1e-9);
    }

    @Override
    public double roundDown(double amount) {
        return Math.floor(amount + 1e-9);
    }
}
