package dev.wulnry.mosskeeper.config;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.util.ColorUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * messages.yml katmani.
 * <p>
 * Tum mesajlar yuklenirken bir kez renklendirilip {@link #cache} icinde
 * tutulur. Calisma aninda yalnizca ucuz {@code String#replace} islemleri
 * yapilir; her mesaj gonderiminde YAML okuma / regex calistirma olmaz.
 */
public final class MessageManager {

    private final MossKeeperLTuccar plugin;
    private final ConfigManager configManager;
    private final File file;

    private final Map<String, String> cache = new HashMap<>(64);
    private final Map<String, List<String>> listCache = new HashMap<>(8);

    private String prefix = "";

    public MessageManager(MossKeeperLTuccar plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration source = YamlConfiguration.loadConfiguration(file);
        writeMissingDefaults(source);
        apply(source);
    }

    /**
     * @param callback basari durumu ile ana thread'de cagrilir
     */
    public void reloadAsync(Consumer<Boolean> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                YamlConfiguration loaded;
                try {
                    if (!file.exists()) {
                        plugin.saveResource("messages.yml", false);
                    }
                    loaded = new YamlConfiguration();
                    loaded.load(file);
                } catch (Exception exception) {
                    plugin.getLogger().warning("messages.yml okunamadi: " + exception.getMessage());
                    syncCallback(callback, false);
                    return;
                }

                final YamlConfiguration result = loaded;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        apply(result);
                        if (callback != null) {
                            callback.accept(true);
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void syncCallback(Consumer<Boolean> callback, boolean success) {
        if (callback == null) {
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                callback.accept(success);
            }
        }.runTask(plugin);
    }

    /**
     * Yeni surumde eklenen mesaj anahtarlarini kullanicinin dosyasina isler;
     * boylece yonetici yeni metinleri gorup duzenleyebilir. Eksik anahtar
     * yoksa dosyaya hic dokunulmaz.
     */
    private void writeMissingDefaults(FileConfiguration target) {
        FileConfiguration defaults = loadDefaults();
        if (defaults == null) {
            return;
        }
        boolean missing = false;
        for (String key : defaults.getKeys(true)) {
            if (!target.contains(key, true)) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return;
        }
        target.setDefaults(defaults);
        target.options().copyDefaults(true);
        try {
            target.save(file);
            plugin.getLogger().info("messages.yml yeni mesajlarla guncellendi.");
        } catch (IOException exception) {
            plugin.getLogger().warning("messages.yml guncellenemedi: " + exception.getMessage());
        }
    }

    private void apply(FileConfiguration source) {
        cache.clear();
        listCache.clear();

        FileConfiguration defaults = loadDefaults();
        if (defaults != null) {
            index(defaults);
        }
        index(source);

        this.prefix = ColorUtil.color(configManager.applyPalette(configManager.getPrefix()));
    }

    private FileConfiguration loadDefaults() {
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            return null;
        }
    }

    private void index(FileConfiguration source) {
        for (String key : source.getKeys(true)) {
            Object value = source.get(key);
            if (value instanceof String text) {
                cache.put(key, configManager.colorize(text));
            } else if (value instanceof List<?> rawList) {
                List<String> lines = new ArrayList<>(rawList.size());
                for (Object entry : rawList) {
                    lines.add(configManager.colorize(String.valueOf(entry)));
                }
                listCache.put(key, lines);
            }
        }
    }

    public String get(String key) {
        String value = cache.get(key);
        return value != null ? value : ("§c[eksik mesaj: " + key + "]");
    }

    public List<String> getList(String key) {
        List<String> value = listCache.get(key);
        return value != null ? value : List.of();
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * Mesaji placeholderlarla doldurur. {@code replacements} ikili gelmelidir:
     * {@code "%urun%", "kuvvetcharm", "%miktar%", "10"}
     */
    public String format(String key, String... replacements) {
        String message = get(key);
        return fill(message, replacements);
    }

    public static String fill(String message, String... replacements) {
        if (replacements == null || replacements.length == 0) {
            return message;
        }
        String result = message;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }

    /**
     * Prefix ekleyerek gonderir. Bos ("") mesajlar sessizce atlanir; boylece
     * yonetici istemedigi bildirimi messages.yml'den bosaltarak kapatabilir.
     */
    public void send(CommandSender target, String key, String... replacements) {
        String message = format(key, replacements);
        if (message.isEmpty()) {
            return;
        }
        target.sendMessage(prefix + message);
    }

    /**
     * Prefix'siz ham satir (liste/yardim ciktilari icin).
     */
    public void sendRaw(CommandSender target, String key, String... replacements) {
        String message = format(key, replacements);
        if (message.isEmpty()) {
            return;
        }
        target.sendMessage(message);
    }

    public void sendList(CommandSender target, String key, String... replacements) {
        for (String line : getList(key)) {
            target.sendMessage(fill(line, replacements));
        }
    }

    /**
     * Takas sonucu bildirimi.
     * <p>
     * Hem sandik hem koylu arayuzu ayni metni kullansin diye tek yerde
     * toplandi; tekil/coklu ayrimi ve tum placeholderlar burada cozulur.
     *
     * @param unitAmount tek takasta verilen adet
     * @param tradeCount kac takas yapildigi
     * @param totalCost  toplam odenen miktar (etkin fiyat uzerinden)
     */
    public void sendTradeResult(CommandSender target, String giveName, int unitAmount,
                                int tradeCount, int totalCost, String costName) {
        int totalGive = unitAmount * tradeCount;
        if (tradeCount > 1) {
            send(target, "trade.success-bulk",
                    "%adet%", String.valueOf(totalGive),
                    "%esya%", giveName,
                    "%odenen%", String.valueOf(totalCost),
                    "%karsilik%", costName);
        } else {
            send(target, "trade.success",
                    "%esya%", unitAmount + "x " + giveName,
                    "%odenen%", String.valueOf(totalCost),
                    "%karsilik%", costName);
        }
    }
}
