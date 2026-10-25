package dev.autobuilder;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;

/** Block prices for contract builds, read from shop-prices.yml. */
final class ShopPrices {

    private final double fallback;
    private final double multiplier;
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);

    ShopPrices(FileConfiguration cfg) {
        this.fallback = Math.max(0, cfg.getDouble("default-price", 1.0));
        this.multiplier = Math.max(0, cfg.getDouble("multiplier", 1.0));
        ConfigurationSection sec = cfg.getConfigurationSection("prices");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m != null) prices.put(m, Math.max(0, sec.getDouble(key)));
        }
    }

    double price(Material m) {
        return prices.getOrDefault(m, fallback) * multiplier;
    }
}
