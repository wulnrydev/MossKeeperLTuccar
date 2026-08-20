package dev.wulnry.mosskeeper.config;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.util.ColorUtil;
import dev.wulnry.mosskeeper.util.TextUtil;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * config.yml okuma/yenileme katmani.
 * <p>
 * Sik erisilen degerler (renkler, GUI boyutu, sesler) alanlarda onbelleklenir;
 * boylece her tick / her tiklamada YAML agacinda gezinme maliyeti odenmez.
 * Yenileme islemi disk I/O'su iceren kismi asenkron yapar, sadece degerlerin
 * uygulanmasi ana thread'e doner.
 */
public final class ConfigManager {

    private final MossKeeperLTuccar plugin;
    private final File file;

    private FileConfiguration config;

    // --- settings -----------------------------------------------------------
    private String prefix = "";
    private long autoSaveTicks = 20L * 60L * 5L;
    private long tradeCooldownMillis = 350L;
    private long npcInteractCooldownMillis = 250L;
    private boolean debug;

    // --- colors -------------------------------------------------------------
    private String colorText = "&7";
    private String colorPrimary = "&#00D420";
    private String colorSecondary = "&#ffd500";
    private String colorError = "&#FF4B4B";

    // --- gui ----------------------------------------------------------------
    /**
     * Takas arayuzu tipi.
     */
    public enum MenuType {
        /** Vanilla koylu takas ekrani (uzeri cizili ana fiyat yerlesik gelir). */
        KOYLU,
        /** Ozel sandik menusu. */
        SANDIK
    }

    private MenuType menuType = MenuType.KOYLU;
    private String guiTitle = "&0Şanslı Tüccar";
    private int guiRows = 6;
    private boolean soundsEnabled = true;
    private Sound soundOpen;
    private Sound soundSuccess;
    private Sound soundFail;

    // --- npc ----------------------------------------------------------------
    private String npcName = "";
    private boolean npcNameVisible = true;
    private Villager.Profession npcProfession = Villager.Profession.LIBRARIAN;
    private Villager.Type npcType = Villager.Type.PLAINS;
    private int npcLevel = 5;
    private Sound npcInteractSound;
    private double npcRemoveRadius = 5.0D;
    private int npcMaxCount = 100;
    private Set<String> npcDisabledWorlds = Set.of();
    private boolean npcRestoreEnabled = true;
    private int npcRestoreMaxAttempts = 3;

    public ConfigManager(MossKeeperLTuccar plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
    }

    /**
     * Sunucu acilisinda bir kez cagrilir (oyuncu yokken, senkron).
     */
    public void load() {
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        if (mergeDefaults(this.config)) {
            writeMergedDefaults(this.config);
        }
        apply(this.config);
    }

    /**
     * Calisan sunucuda yenileme: dosya okuma asenkron, uygulama senkron.
     *
     * @param callback basari durumu ile ana thread'de cagrilir
     */
    public void reloadAsync(Consumer<Boolean> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                YamlConfiguration loaded;
                try {
                    if (!file.exists()) {
                        plugin.saveResource("config.yml", false);
                    }
                    loaded = new YamlConfiguration();
                    loaded.load(file);
                    mergeDefaults(loaded);
                } catch (Exception exception) {
                    plugin.getLogger().warning("config.yml okunamadi: " + exception.getMessage());
                    syncCallback(callback, false);
                    return;
                }

                final YamlConfiguration result = loaded;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        config = result;
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
     * Jar icindeki varsayilanlari, kullanicinin dosyasindaki eksik anahtarlar
     * icin yedek olarak baglar (surum yukseltmelerinde NPE olusmaz).
     *
     * @return kullanicinin dosyasinda eksik anahtar var mi (yani dosya
     *         guncellenmeli mi)
     */
    private boolean mergeDefaults(FileConfiguration target) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return false;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);

                // Eksik anahtar tespiti, copyDefaults acilmadan ONCE yapilmali;
                // sonrasinda contains(...) varsayilanlari da gorur.
                boolean missing = false;
                for (String key : defaults.getKeys(true)) {
                    if (!target.contains(key, true)) {
                        missing = true;
                        break;
                    }
                }

                target.setDefaults(defaults);
                target.options().copyDefaults(true);
                return missing;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Varsayilan config gomulemedi: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Yeni surumde eklenen anahtarlari kullanicinin dosyasina isler.
     * <p>
     * Yalnizca gercekten eksik anahtar varsa cagrilir; her aciliskta dosyayi
     * gereksiz yere yeniden yazmayiz. Bukkit 1.18+ yorum satirlarini korur.
     */
    private void writeMergedDefaults(FileConfiguration target) {
        try {
            target.save(file);
            plugin.getLogger().info("config.yml yeni ayarlarla guncellendi.");
        } catch (IOException exception) {
            plugin.getLogger().warning("config.yml guncellenemedi: " + exception.getMessage());
        }
    }

    private void apply(FileConfiguration cfg) {
        this.prefix = cfg.getString("settings.prefix", "&#15FF08Şanslı Tüccar &8▸ ");
        this.autoSaveTicks = Math.max(1L, cfg.getLong("settings.auto-save-minutes", 5L)) * 60L * 20L;
        this.tradeCooldownMillis = Math.max(0L, cfg.getLong("settings.trade-cooldown-ms", 350L));
        this.npcInteractCooldownMillis = Math.max(0L, cfg.getLong("settings.npc-interact-cooldown-ms", 250L));
        this.debug = cfg.getBoolean("settings.debug", false);

        this.colorText = cfg.getString("colors.text", "&7");
        this.colorPrimary = cfg.getString("colors.primary", "&#00D420");
        this.colorSecondary = cfg.getString("colors.secondary", "&#ffd500");
        this.colorError = cfg.getString("colors.error", "&#FF4B4B");

        this.menuType = parseMenuType(cfg.getString("gui.menu-type", "KOYLU"));
        this.guiTitle = cfg.getString("gui.title", "&0Şanslı Tüccar");
        this.guiRows = Math.max(3, Math.min(6, cfg.getInt("gui.rows", 6)));
        this.soundsEnabled = cfg.getBoolean("gui.sounds.enabled", true);
        this.soundOpen = parseSound(cfg.getString("gui.sounds.open"));
        this.soundSuccess = parseSound(cfg.getString("gui.sounds.success"));
        this.soundFail = parseSound(cfg.getString("gui.sounds.fail"));

        this.npcName = cfg.getString("npc.name", "&#15FF08Şanslı Tüccar &8▸ &7Tıkla!");
        this.npcNameVisible = cfg.getBoolean("npc.name-always-visible", true);
        this.npcProfession = fromRegistry(Registry.VILLAGER_PROFESSION, cfg.getString("npc.profession"), Villager.Profession.LIBRARIAN);
        this.npcType = fromRegistry(Registry.VILLAGER_TYPE, cfg.getString("npc.type"), Villager.Type.PLAINS);
        this.npcLevel = Math.max(1, Math.min(5, cfg.getInt("npc.level", 5)));
        this.npcInteractSound = parseSound(cfg.getString("npc.interact-sound"));
        this.npcRemoveRadius = Math.max(1.0D, cfg.getDouble("npc.remove-radius", 5.0D));
        this.npcMaxCount = Math.max(1, cfg.getInt("npc.max-count", 100));
        this.npcDisabledWorlds = parseWorlds(cfg.getStringList("npc.disabled-worlds"));
        this.npcRestoreEnabled = cfg.getBoolean("npc.restore.enabled", true);
        this.npcRestoreMaxAttempts = Math.max(1, cfg.getInt("npc.restore.max-attempts", 3));
    }

    private Set<String> parseWorlds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim().toLowerCase(Locale.ENGLISH));
            }
        }
        return result;
    }

    /**
     * "KÖYLÜ", "koylu", "VILLAGER", "MERCHANT" -> KOYLU
     * "SANDIK", "chest", "gui"                 -> SANDIK
     */
    private MenuType parseMenuType(String name) {
        String normalized = TextUtil.normalize(name);
        return switch (normalized) {
            case "sandik", "chest", "gui", "menu" -> MenuType.SANDIK;
            case "koylu", "villager", "merchant", "tuccar", "trader" -> MenuType.KOYLU;
            default -> {
                plugin.getLogger().warning("Gecersiz gui.menu-type degeri: " + name + " (KOYLU kullanildi)");
                yield MenuType.KOYLU;
            }
        };
    }

    /**
     * Ses adini cozer.
     * <p>
     * {@code Sound} 1.21.3'ten itibaren enum degildir; bu yuzden
     * {@code Sound.valueOf} yerine {@link Registry} uzerinden aranir - bu yol
     * hem 1.20'de hem sonraki surumlerde calisir. Iki yazim da kabul edilir:
     * enum adi ({@code BLOCK_NOTE_BLOCK_HARP}) ve namespaced anahtar
     * ({@code block.note_block.harp}).
     */
    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = name.trim().toLowerCase(Locale.ENGLISH);
        if (needle.startsWith("minecraft:")) {
            needle = needle.substring("minecraft:".length());
        }
        String underscored = needle.replace('.', '_');
        try {
            for (Sound sound : Registry.SOUNDS) {
                String key = sound.getKey().getKey();
                if (key.equals(needle) || key.replace('.', '_').equals(underscored)) {
                    return sound;
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Ses kayitlari okunamadi: " + throwable.getMessage());
            return null;
        }
        plugin.getLogger().warning("Gecersiz/desteklenmeyen ses adi: " + name + " (bu ses kapatildi)");
        return null;
    }

    /**
     * Enum yerine Registry kullaniyoruz: Villager.Profession/Type ileriki
     * surumlerde enum olmaktan cikti, Registry erisimi her surumde calisir.
     */
    private <T extends Keyed> T fromRegistry(Registry<T> registry, String name, T fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase(Locale.ENGLISH).trim());
            T value = registry.get(key);
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
            // gecersiz key formati -> varsayilana dus
        }
        plugin.getLogger().warning("Gecersiz NPC degeri: " + name + " (varsayilan kullanildi)");
        return fallback;
    }

    /**
     * Mesaj/lore icindeki renk placeholderlarini paletten doldurur.
     */
    public String applyPalette(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String result = input;
        if (result.indexOf('%') != -1) {
            result = result.replace("%text%", colorText)
                    .replace("%primary%", colorPrimary)
                    .replace("%secondary%", colorSecondary)
                    .replace("%error%", colorError);
        }
        return result;
    }

    /**
     * Palet + renk kodu cevirisi tek adimda.
     */
    public String colorize(String input) {
        return ColorUtil.color(applyPalette(input));
    }

    public FileConfiguration raw() {
        return config;
    }

    public String getPrefix() {
        return prefix;
    }

    public long getAutoSaveTicks() {
        return autoSaveTicks;
    }

    public long getTradeCooldownMillis() {
        return tradeCooldownMillis;
    }

    public long getNpcInteractCooldownMillis() {
        return npcInteractCooldownMillis;
    }

    public boolean isDebug() {
        return debug;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public String getGuiTitle() {
        return guiTitle;
    }

    public int getGuiRows() {
        return guiRows;
    }

    public int getGuiSize() {
        return guiRows * 9;
    }

    public boolean isSoundsEnabled() {
        return soundsEnabled;
    }

    public Sound getSoundOpen() {
        return soundOpen;
    }

    public Sound getSoundSuccess() {
        return soundSuccess;
    }

    public Sound getSoundFail() {
        return soundFail;
    }

    public String getNpcName() {
        return npcName;
    }

    public boolean isNpcNameVisible() {
        return npcNameVisible;
    }

    public Villager.Profession getNpcProfession() {
        return npcProfession;
    }

    public Villager.Type getNpcType() {
        return npcType;
    }

    public int getNpcLevel() {
        return npcLevel;
    }

    public Sound getNpcInteractSound() {
        return npcInteractSound;
    }

    public double getNpcRemoveRadius() {
        return npcRemoveRadius;
    }

    public int getNpcMaxCount() {
        return npcMaxCount;
    }

    public boolean isNpcWorldDisabled(String worldName) {
        return worldName != null && npcDisabledWorlds.contains(worldName.toLowerCase(Locale.ENGLISH));
    }

    public boolean isNpcRestoreEnabled() {
        return npcRestoreEnabled;
    }

    public int getNpcRestoreMaxAttempts() {
        return npcRestoreMaxAttempts;
    }

    /**
     * Yalnizca ilk kurulumda gerekli; sunucu klasorunun hazir oldugunu garanti eder.
     */
    public void ensureDataFolder() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
        } catch (IOException exception) {
            plugin.getLogger().severe("Veri klasoru olusturulamadi: " + exception.getMessage());
        }
    }
}
