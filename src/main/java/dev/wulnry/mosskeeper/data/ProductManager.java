package dev.wulnry.mosskeeper.data;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.storage.YamlStore;
import dev.wulnry.mosskeeper.util.ItemUtil;
import dev.wulnry.mosskeeper.util.TextUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Takas profillerinin bellek ici deposu.
 *
 * <h3>Thread modeli</h3>
 * <ul>
 *   <li>{@code products} haritasina <b>yalnizca ana thread</b> dokunur -> kilit gerekmez.</li>
 *   <li>Disk islemleri {@link YamlStore}'a devredilmistir: asenkron, sirali ve atomik.</li>
 *   <li>Esya (de)serilestirme Bukkit API'sine dokundugu icin ana thread'de
 *       yapilir; asenkron kisim sadece hazir YAML agacini tasir.</li>
 * </ul>
 */
public final class ProductManager {

    private final MossKeeperLTuccar plugin;
    private final YamlStore store;

    private final Map<String, TradeProduct> products = new LinkedHashMap<>();

    /** Her degisiklikte artar; GUI onbellegi bu sayaca bakarak tazelenir. */
    private int revision;

    public ProductManager(MossKeeperLTuccar plugin) {
        this.plugin = plugin;
        this.store = new YamlStore(plugin, "products.yml");
    }

    public boolean isLoaded() {
        return store.isLoaded();
    }

    public boolean isDirty() {
        return store.isDirty();
    }

    public void markDirty() {
        store.markDirty();
        this.revision++;
    }

    public int getRevision() {
        return revision;
    }

    // ------------------------------------------------------------------ CRUD

    public TradeProduct get(String name) {
        return products.get(TextUtil.normalize(name));
    }

    public boolean exists(String name) {
        return products.containsKey(TextUtil.normalize(name));
    }

    public TradeProduct create(String displayName) {
        TradeProduct product = new TradeProduct(displayName);
        products.put(product.getId(), product);
        markDirty();
        return product;
    }

    public boolean delete(String name) {
        boolean removed = products.remove(TextUtil.normalize(name)) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public Collection<TradeProduct> all() {
        return products.values();
    }

    public int size() {
        return products.size();
    }

    // --------------------------------------------------------------- LOADING

    /**
     * Dosyayi asenkron okur, esyalari ana thread'de coz(umle)r.
     * Yeniden cagrildiginda mevcut veriyi diskteki ile degistirir.
     */
    public void loadAsync(Runnable afterLoad) {
        store.loadAsync(this::applyLoaded, afterLoad);
    }

    private void applyLoaded(YamlConfiguration data) {
        products.clear();
        revision++; // GUI onbellegini gecersiz kil

        ConfigurationSection root = data.getConfigurationSection("products");
        if (root == null) {
            plugin.getLogger().info("Yuklenen takas profili: 0");
            return;
        }

        int broken = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String displayName = section.getString("display-name", key);
            TradeProduct product = new TradeProduct(TextUtil.normalize(key), displayName);
            product.setMainPrice(Math.max(0, section.getInt("main-price", 0)));
            product.setDiscountPrice(Math.max(0, section.getInt("discount-price", 0)));

            // Esyalar ayri ayri cozulur: biri bozuksa profilin tamami degil,
            // yalnizca o alan kaybolur ve yonetici eksigi komutla tamamlayabilir.
            broken += decodeInto(product, section.getString("give-item"), true) ? 0 : 1;
            broken += decodeInto(product, section.getString("cost-item"), false) ? 0 : 1;

            products.put(product.getId(), product);
        }

        plugin.getLogger().info("Yuklenen takas profili: " + products.size()
                + (broken > 0 ? " (" + broken + " bozuk esya kaydi atlandi)" : ""));
    }

    /**
     * @return esya cozulebildi mi (veri hic yoksa da {@code true})
     */
    private boolean decodeInto(TradeProduct product, String encoded, boolean give) {
        if (encoded == null || encoded.isEmpty()) {
            return true;
        }
        try {
            ItemStack item = ItemUtil.decode(encoded);
            if (give) {
                product.restoreGiveItem(item, encoded);
            } else {
                product.restoreCostItem(item, encoded);
            }
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("'" + product.getId() + "' profilinin "
                    + (give ? "verilecek" : "karsilik") + " esyasi okunamadi: " + exception.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------------- SAVING

    /**
     * Ana thread'de anlik goruntu alir, diske yazmayi asenkron kuyruga atar.
     */
    public void saveAsync() {
        store.saveAsync(snapshot());
    }

    /**
     * Yalnizca {@code onDisable} icin: scheduler kapali oldugundan senkron yazar.
     */
    public void saveNow() {
        store.saveNow(snapshot());
    }

    /**
     * Bellekteki profilleri YAML agacina cevirir (ana thread - Bukkit API).
     */
    private YamlConfiguration snapshot() {
        YamlConfiguration data = new YamlConfiguration();
        for (TradeProduct product : products.values()) {
            String path = "products." + product.getId();
            data.set(path + ".display-name", product.getDisplayName());
            data.set(path + ".main-price", product.getMainPrice());
            data.set(path + ".discount-price", product.getDiscountPrice());
            data.set(path + ".give-item", encodeSafely(product, true));
            data.set(path + ".cost-item", encodeSafely(product, false));
        }
        return data;
    }

    private String encodeSafely(TradeProduct product, boolean give) {
        try {
            return give ? product.getGiveItemData() : product.getCostItemData();
        } catch (Exception exception) {
            plugin.getLogger().warning("'" + product.getId() + "' esyasi kaydedilemedi: " + exception.getMessage());
            return null;
        }
    }
}
