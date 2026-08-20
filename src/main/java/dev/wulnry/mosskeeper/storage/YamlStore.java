package dev.wulnry.mosskeeper.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Sirali, atomik ve asenkron YAML kalicilik katmani.
 *
 * <h3>Neden ayri bir sinif?</h3>
 * Urun ve NPC depolari ayni uc problemi cozmek zorunda:
 * <ul>
 *   <li><b>Sira garantisi:</b> pes pese kuyruga atilan iki asenkron yazma,
 *       thread havuzunda <i>ters sirada</i> calisabilir ve eski anlik goruntu
 *       yeninin uzerine yazabilir. Her yazmaya artan bir sira numarasi verilir;
 *       daha yenisi zaten yazilmissa eski atlanir.</li>
 *   <li><b>Atomiklik:</b> hedef dosyaya dogrudan yazmak, yazma sirasindaki bir
 *       cokmede dosyayi yarim birakir ve <i>tum veri</i> gider. Once
 *       {@code .tmp}'ye yazilir, sonra atomik olarak tasinir; bir onceki surum
 *       {@code .bak} olarak saklanir ve okuma basarisiz olursa ondan donulur.</li>
 *   <li><b>Dogru "kirli" yonetimi:</b> bayrak, yazma <i>gercekten basarili</i>
 *       oldugunda temizlenir. Boylece basarisiz bir yazma, otomatik kaydin bir
 *       daha denememesine ve verinin sessizce kaybolmasina yol acmaz.</li>
 * </ul>
 *
 * <h3>Thread modeli</h3>
 * {@code markDirty} / {@code isDirty} / anlik goruntu alma <b>ana thread</b>e
 * aittir. Yalnizca disk erisimi asenkrondur ve {@link #ioLock} ile korunur.
 */
public final class YamlStore {

    private final Plugin plugin;
    private final String fileName;
    private final File file;

    private final ReentrantLock ioLock = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();
    private volatile long lastWrittenSequence = -1L;

    private volatile boolean loaded;
    private boolean dirty;
    /** Her veri degisikliginde artar; yazma bitiminde bayragin guvenle temizlenmesini saglar. */
    private long changeVersion;

    public YamlStore(Plugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.file = new File(plugin.getDataFolder(), fileName);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
        this.changeVersion++;
    }

    // ---------------------------------------------------------------- OKUMA

    /**
     * Dosyayi asenkron okur; {@code apply} ana thread'de calisir (Bukkit API
     * guvenligi icin). {@code afterLoad} uygulamadan hemen sonra tetiklenir.
     */
    public void loadAsync(Consumer<YamlConfiguration> apply, Runnable afterLoad) {
        new BukkitRunnable() {
            @Override
            public void run() {
                final YamlConfiguration data = read();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        apply.accept(data);
                        loaded = true;
                        dirty = false;
                        if (afterLoad != null) {
                            afterLoad.run();
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private YamlConfiguration read() {
        ioLock.lock();
        try {
            YamlConfiguration primary = tryRead(file);
            if (primary != null) {
                return primary;
            }
            YamlConfiguration backup = tryRead(backupFile());
            if (backup != null) {
                plugin.getLogger().warning(fileName + " okunamadi; yedekten (" + fileName + ".bak) devam ediliyor.");
                return backup;
            }
            return new YamlConfiguration();
        } finally {
            ioLock.unlock();
        }
    }

    private YamlConfiguration tryRead(File source) {
        if (!source.exists()) {
            return null;
        }
        try {
            YamlConfiguration data = new YamlConfiguration();
            data.load(source);
            return data;
        } catch (Exception exception) {
            plugin.getLogger().severe(source.getName() + " okunamadi: " + exception.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------------- YAZMA

    /**
     * Ana thread'de alinmis anlik goruntuyu asenkron olarak diske yazar.
     */
    public void saveAsync(YamlConfiguration snapshot) {
        if (!loaded) {
            return;
        }
        final long seq = sequence.incrementAndGet();
        final long version = changeVersion;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!write(snapshot, seq)) {
                    return;
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Yazma sirasinda yeni bir degisiklik olduysa kirli kalmali.
                        if (changeVersion == version) {
                            dirty = false;
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Yalnizca {@code onDisable} icin: bu noktada scheduler kapali oldugundan
     * asenkron gorev baslatilamaz, veri kaybini onlemek adina senkron yazariz.
     */
    public void saveNow(YamlConfiguration snapshot) {
        if (!loaded) {
            return;
        }
        if (write(snapshot, sequence.incrementAndGet())) {
            dirty = false;
        }
    }

    private boolean write(YamlConfiguration data, long seq) {
        ioLock.lock();
        try {
            if (seq < lastWrittenSequence) {
                // Daha yeni bir anlik goruntu zaten yazildi; bunu yazmak geriye donus olurdu.
                return false;
            }
            Path target = file.toPath();
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temp = target.resolveSibling(fileName + ".tmp");
            data.save(temp.toFile());

            if (Files.exists(target)) {
                Files.copy(target, backupFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            move(temp, target);

            lastWrittenSequence = seq;
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe(fileName + " kaydedilemedi: " + exception.getMessage());
            return false;
        } finally {
            ioLock.unlock();
        }
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // Bazi dosya sistemleri (ag surucusu vb.) atomik tasimayi desteklemez.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File backupFile() {
        return new File(file.getParentFile(), fileName + ".bak");
    }
}
