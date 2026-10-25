package dev.autobuilder;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/** Money through Vault (reached by reflection, so Vault is optional and nothing is bundled). */
final class VaultWallet implements Wallet {

    private final Object economy;
    private final Method balance, withdraw, deposit, success, format;
    private final String provider;

    static VaultWallet create(AutoBuilderPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        try {
            Class<?> ec = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ec);
            if (rsp == null) return null;
            return new VaultWallet(ec, rsp.getProvider());
        } catch (Throwable t) {
            plugin.getLogger().warning("Vault was found but its economy could not be used: " + t);
            return null;
        }
    }

    private VaultWallet(Class<?> ec, Object economy) throws Exception {
        this.economy = economy;
        this.balance = ec.getMethod("getBalance", OfflinePlayer.class);
        this.withdraw = ec.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        this.deposit = ec.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        this.success = withdraw.getReturnType().getMethod("transactionSuccess");
        this.format = ec.getMethod("format", double.class);
        String n;
        try {
            n = String.valueOf(ec.getMethod("getName").invoke(economy));
        } catch (Throwable t) {
            n = "unknown economy";
        }
        this.provider = n;
    }

    @Override
    public String name() {
        return "Vault -> " + provider;
    }

    @Override
    public String currencyName() {
        return "money";
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        try {
            double have = (Double) balance.invoke(economy, player);
            if (have + 1e-9 < amount) return false;
            Object response = withdraw.invoke(economy, player, amount);
            Boolean ok = (Boolean) success.invoke(response);
            return ok != null && ok;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        try {
            deposit.invoke(economy, player, amount);
        } catch (Throwable ignored) {
            // the economy plugin refused; nothing more we can do
        }
    }

    @Override
    public String format(double amount) {
        try {
            return String.valueOf(format.invoke(economy, amount));
        } catch (Throwable t) {
            return String.format("%.2f", amount);
        }
    }

    @Override
    public double round(double amount) {
        return Math.ceil(amount * 100.0 - 1e-9) / 100.0;
    }

    @Override
    public double roundDown(double amount) {
        return Math.floor(amount * 100.0 + 1e-9) / 100.0;
    }
}
