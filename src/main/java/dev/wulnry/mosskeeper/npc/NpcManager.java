package dev.wulnry.mosskeeper.npc;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.storage.YamlStore;
import dev.wulnry.mosskeeper.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Citizens vb. harici bagimlilik olmadan, saf Bukkit API ile calisan hafif
 * tuccar NPC sistemi.
 *
 * <h3>Neden lag yaratmaz?</h3>
 * <ul>
 *   <li>NPC {@code setAI(false)} + {@code setAware(false)} ile tick basina
 *       pathfinding/AI hesabi yapmaz.</li>
 *   <li>Chunk olaylari her chunk icin degil, yalnizca kayitli NPC barindiran
 *       chunk anahtarlari icin is yapar (O(1) long-key kontrolu).</li>
 *   <li>Disk islemleri {@link YamlStore} uzerinden asenkron, sirali ve atomiktir.</li>
 * </ul>
 *
 * <h3>Kalicilik ve duplikasyon korumasi</h3>
 * NPC'ler zaten {@code setPersistent(true)} ile dunyaya kaydedilir; buradaki
 * geri yukleme mantigi mob temizleyici / {@code /kill} gibi <b>dis
 * mudahalelere</b> karsi bir emniyet agidir. Kritik nokta sudur:
 * <b>{@code ChunkLoadEvent} anında entity'ler henuz yuklenmis olmayabilir</b>
 * (Paper 1.17+ entity'leri chunk'tan ayri depolar). O anda "NPC yok" sanip
 * yenisini dogurmak, dunyada suresiz birikien kopya koyluler uretir.
 * Bu yuzden dogrulama yalnizca entity deposunun yuklu oldugu <b>kesin</b>
 * oldugu anda yapilir ({@code EntitiesLoadEvent} ya da
 * {@link Chunk#isEntitiesLoaded()}), ve dogurmadan once uc asamali kontrol
 * uygulanir: UUID ile ara -> yakindaki sahipsiz tuccari sahiplen -> ancak
 * o zaman yeniden dogur (deneme sayaci ile sinirli).
 */
public final class NpcManager {

    /** Sahiplenme toleransi (blok). */
    private static final double ADOPT_TOLERANCE = 1.0D;

    /**
     * NPC yerlestirme sonucu.
     */
    public enum SpawnResult {
        SUCCESS,
        /** Dunya kara listede ya da gecersiz. */
        WORLD_DISABLED,
        /** {@code npc.max-count} sinirina ulasildi. */
        LIMIT_REACHED,
        /** Baska bir eklenti {@code CreatureSpawnEvent}'i iptal etti. */
        BLOCKED
    }

    private final MossKeeperLTuccar plugin;
    private final ConfigManager configManager;
    private final YamlStore store;

    /** NPC'yi tanimlayan kalici etiket (entity NBT'sinde saklanir). */
    private final NamespacedKey markerKey;

    private final Map<UUID, TraderNpc> npcs = new LinkedHashMap<>();
    /**
     * dunya <b>adi</b> -> (chunkKey -> NPC uuid listesi).
     * <p>
     * Dunya UUID'si yerine ad kullaniyoruz: kayitlar, dunyasi henuz
     * yuklenmemisken de indekslenebilmeli (Multiverse gibi eklentiler dunyalari
     * bizden sonra yukler). Aksi halde o NPC'ler hic geri gelmezdi.
     */
    private final Map<String, Map<Long, List<UUID>>> chunkIndex = new HashMap<>();

    public NpcManager(MossKeeperLTuccar plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.store = new YamlStore(plugin, "npcs.yml");
        this.markerKey = new NamespacedKey(plugin, "lucky_trader");
    }

    public boolean isDirty() {
        return store.isDirty();
    }

    public int size() {
        return npcs.size();
    }

    // ------------------------------------------------------------- TANIMLAMA

    /**
     * Bu entity bizim tuccarimiz mi? (PDC etiketi ile)
     */
    public boolean isTrader(Entity entity) {
        if (!(entity instanceof Villager)) {
            return false;
        }
        PersistentDataContainer container = entity.getPersistentDataContainer();
        return container.has(markerKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- SPAWN

    /**
     * Yoneticinin durdugu konuma, baktigi yone donuk bir tuccar dogurur.
     */
    public SpawnResult spawn(Location location) {
        World world = location.getWorld();
        if (world == null || configManager.isNpcWorldDisabled(world.getName())) {
            return SpawnResult.WORLD_DISABLED;
        }
        if (npcs.size() >= configManager.getNpcMaxCount()) {
            return SpawnResult.LIMIT_REACHED;
        }

        // Consumer overload'i, entity dunyaya eklenmeden ONCE ayarlanmasini
        // saglar; boylece tek bir tick bile AI calismaz.
        Villager villager = world.spawn(location, Villager.class, this::configure);

        // CraftBukkit, CreatureSpawnEvent iptal edilse bile Bukkit nesnesini
        // dondurur ama entity'yi dunyaya eklemez. Hayalet kayit birakmamak icin
        // gecerliligi dogrulamak sart.
        if (!villager.isValid()) {
            villager.remove();
            return SpawnResult.BLOCKED;
        }

        register(TraderNpc.of(villager.getUniqueId(), location));
        markDirtyAndSave();
        return SpawnResult.SUCCESS;
    }

    /**
     * NPC'nin tum optimizasyon ve gorunum ayarlarini uygular.
     * Hem ilk dogusta hem de chunk yeniden yuklendiginde cagrilir.
     */
    public void configure(Villager villager) {
        villager.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);

        // --- optimizasyon: sifir AI, sifir fizik, sifir ses ---
        villager.setAI(false);
        villager.setAware(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setCanPickupItems(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);

        // --- vanilla ticaret verisini tamamen bosalt ---
        villager.setRecipes(Collections.emptyList());
        villager.setVillagerExperience(0);

        // --- gorunum ---
        villager.setProfession(configManager.getNpcProfession());
        villager.setVillagerType(configManager.getNpcType());
        villager.setVillagerLevel(configManager.getNpcLevel());
        villager.setCustomName(ColorUtil.color(configManager.applyPalette(configManager.getNpcName())));
        villager.setCustomNameVisible(configManager.isNpcNameVisible());
    }

    /**
     * Config yenilendiginde yuklu tum NPC'lerin gorunumunu tazeler.
     */
    public int refreshLoaded() {
        int count = 0;
        for (TraderNpc npc : npcs.values()) {
            if (Bukkit.getEntity(npc.getEntityId()) instanceof Villager villager && villager.isValid()) {
                configure(villager);
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------------------- SILME

    /**
     * Oyuncunun baktigi ya da yakinindaki tuccari siler.
     *
     * @return silme basarili mi
     */
    public boolean removeNearest(Player player) {
        double radius = configManager.getNpcRemoveRadius();

        // 1) Once tam olarak baktigi entity'yi dene (hassas secim).
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                radius,
                this::isTrader);

        Entity target = result != null ? result.getHitEntity() : null;

        // 2) Bakmiyorsa, yaricap icindeki en yakin tuccari sec.
        if (target == null) {
            double closest = Double.MAX_VALUE;
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (!isTrader(entity)) {
                    continue;
                }
                double distance = entity.getLocation().distanceSquared(player.getLocation());
                if (distance < closest) {
                    closest = distance;
                    target = entity;
                }
            }
        }

        if (target == null) {
            return false;
        }

        unregister(target.getUniqueId());
        target.remove();
        markDirtyAndSave();
        return true;
    }

    // -------------------------------------------------------------- KAYITLAR

    private void register(TraderNpc npc) {
        npcs.put(npc.getEntityId(), npc);
        worldIndex(npc.getWorldName())
                .computeIfAbsent(npc.getChunkKey(), key -> new ArrayList<>(1))
                .add(npc.getEntityId());
    }

    private void unregister(UUID entityId) {
        TraderNpc npc = npcs.remove(entityId);
        if (npc == null) {
            return;
        }
        Map<Long, List<UUID>> worldIndex = chunkIndex.get(npc.getWorldName());
        if (worldIndex == null) {
            return;
        }
        List<UUID> list = worldIndex.get(npc.getChunkKey());
        if (list != null) {
            list.remove(entityId);
            if (list.isEmpty()) {
                worldIndex.remove(npc.getChunkKey());
            }
        }
        if (worldIndex.isEmpty()) {
            chunkIndex.remove(npc.getWorldName());
        }
    }

    private Map<Long, List<UUID>> worldIndex(String worldName) {
        return chunkIndex.computeIfAbsent(worldName, key -> new HashMap<>());
    }

    // ------------------------------------------------------------ GERI YUKLEME

    /**
     * {@code EntitiesLoadEvent} icin: entity listesi kesin olarak hazirdir.
     */
    public void handleEntitiesLoaded(Chunk chunk, List<Entity> entities) {
        restore(chunk, entities, true);
    }

    /**
     * {@code ChunkLoadEvent} icin yedek yol - <b>asla yeniden dogurmaz.</b>
     * <p>
     * Entity deposu henuz yuklenmemisse hicbir sey yapmayiz. Yuklenmis gorunse
     * bile liste eksik olabileceginden bu yolda yalnizca <i>mevcut</i> NPC'ler
     * tazelenir; "kayip" karari ve yeniden dogurma kesin entity listesi veren
     * {@code EntitiesLoadEvent}'e birakilir. Boylece kopya NPC uretme ihtimali
     * tamamen ortadan kalkar.
     */
    public void handleChunkLoad(Chunk chunk) {
        if (!chunk.isEntitiesLoaded()) {
            return;
        }
        restore(chunk, null, false);
    }

    /**
     * Dunya sonradan yuklendiginde (Multiverse vb.) o dunyanin kayitlarini
     * dogrular.
     */
    public void handleWorldLoad(World world) {
        Map<Long, List<UUID>> worldIndex = chunkIndex.get(world.getName());
        if (worldIndex == null || worldIndex.isEmpty()) {
            return;
        }
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isEntitiesLoaded()) {
                // Acilis/dunya yukleme aninda chunk uzun suredir yuklu oldugundan
                // entity listesi guvenilirdir; burada dogurmaya izin verilir.
                restore(chunk, null, true);
            }
        }
    }

    /**
     * Sunucu acilisinda zaten yuklu olan chunk'lardaki NPC'leri tazeler.
     */
    public void refreshLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            handleWorldLoad(world);
        }
    }

    /**
     * Kayitli NPC'leri dogrular: varsa ayarlarini tazeler, gercekten silinmisse
     * ayni konumda yeniden dogurur.
     *
     * @param known      {@code EntitiesLoadEvent}'ten gelen kesin liste; {@code null}
     *                   ise chunk'tan okunur (entity deposunun yuklu oldugu dogrulanmis olmali)
     * @param allowSpawn entity listesinin eksiksiz oldugu garanti ediliyorsa
     *                   {@code true}. {@code false} iken kayip NPC yeniden
     *                   dogurulmaz - yalnizca mevcut olanlar tazelenir.
     */
    private void restore(Chunk chunk, Collection<Entity> known, boolean allowSpawn) {
        if (!store.isLoaded() || chunkIndex.isEmpty()) {
            return;
        }
        Map<Long, List<UUID>> worldIndex = chunkIndex.get(chunk.getWorld().getName());
        if (worldIndex == null || worldIndex.isEmpty()) {
            return;
        }
        List<UUID> expected = worldIndex.get(TraderNpc.chunkKey(chunk.getX(), chunk.getZ()));
        if (expected == null || expected.isEmpty()) {
            return;
        }

        Collection<Entity> entities = known != null ? known : List.of(chunk.getEntities());

        // Bu chunk'taki tum tuccar villagerlarini topla.
        Map<UUID, Villager> byId = new HashMap<>(expected.size());
        List<Villager> traders = new ArrayList<>(expected.size());
        for (Entity entity : entities) {
            if (entity instanceof Villager villager && villager.isValid() && isTrader(villager)) {
                byId.put(villager.getUniqueId(), villager);
                traders.add(villager);
            }
        }

        boolean changed = false;
        // expected, dongu icinde register/unregister ile degisebilir -> kopya uzerinde gez.
        for (UUID id : new ArrayList<>(expected)) {
            TraderNpc npc = npcs.get(id);
            if (npc == null) {
                continue;
            }

            // 1) Beklenen UUID bu chunk'ta mi?
            Villager villager = byId.get(id);
            if (villager != null) {
                configure(villager);
                npc.resetRestoreAttempts();
                continue;
            }

            // 2) Entity yasiyor ama baska bir chunk'a kaymis olabilir.
            if (Bukkit.getEntity(id) instanceof Villager loaded && loaded.isValid()) {
                configure(loaded);
                npc.resetRestoreAttempts();
                continue;
            }

            // 3) Konumunda sahipsiz bir tuccar var mi? (UUID degismis olabilir)
            Villager orphan = findOrphan(npc, traders);
            if (orphan != null) {
                reassign(npc, orphan.getUniqueId());
                configure(orphan);
                npc.resetRestoreAttempts();
                changed = true;
                continue;
            }

            // 4) Gercekten kayip -> yeniden dogur (yalnizca liste kesinse).
            if (allowSpawn && respawn(npc)) {
                changed = true;
            }
        }

        if (changed) {
            markDirtyAndSave();
        }
    }

    /**
     * Kayitli konumda duran, hicbir kayda ait olmayan tuccar villager'i bulur.
     * Boyle biri varsa yenisini dogurmak yerine onu sahiplenmek gerekir -
     * aksi halde her chunk yuklemesinde bir kopya daha birikirdi.
     */
    private Villager findOrphan(TraderNpc npc, List<Villager> candidates) {
        for (Villager villager : candidates) {
            if (npcs.containsKey(villager.getUniqueId())) {
                continue; // baska bir kaydin NPC'si
            }
            if (npc.isAt(villager.getLocation(), ADOPT_TOLERANCE)) {
                return villager;
            }
        }
        return null;
    }

    /**
     * @return yeniden dogurma yapildi mi
     */
    private boolean respawn(TraderNpc npc) {
        if (!configManager.isNpcRestoreEnabled()) {
            return false;
        }
        int maxAttempts = configManager.getNpcRestoreMaxAttempts();
        if (npc.getRestoreAttempts() >= maxAttempts) {
            if (npc.getRestoreAttempts() == maxAttempts) {
                npc.countRestoreAttempt(); // uyariyi bir kez bas
                plugin.getLogger().warning("Tuccar NPC'si " + maxAttempts
                        + " denemede olusturulamadi, vazgecildi: " + describe(npc)
                        + " (baska bir eklenti entity'yi siliyor olabilir)");
            }
            return false;
        }

        Location location = npc.toLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }

        Villager respawned = location.getWorld().spawn(location, Villager.class, this::configure);
        npc.countRestoreAttempt();
        if (!respawned.isValid()) {
            respawned.remove();
            return false;
        }

        reassign(npc, respawned.getUniqueId());
        if (configManager.isDebug()) {
            plugin.getLogger().info("Kayip tuccar yeniden olusturuldu: " + describe(npc));
        }
        return true;
    }

    /** Kaydi yeni bir entity UUID'sine baglar (indeks tutarli kalir). */
    private void reassign(TraderNpc npc, UUID newId) {
        unregister(npc.getEntityId());
        npc.setEntityId(newId);
        register(npc);
    }

    private String describe(TraderNpc npc) {
        return npc.getWorldName() + " " + (int) npc.getX() + ", " + (int) npc.getY() + ", " + (int) npc.getZ();
    }

    // ------------------------------------------------------------ KALICILIK

    public void loadAsync(Runnable afterLoad) {
        store.loadAsync(this::applyLoaded, afterLoad);
    }

    private void applyLoaded(YamlConfiguration data) {
        npcs.clear();
        chunkIndex.clear();

        ConfigurationSection root = data.getConfigurationSection("npcs");
        if (root == null) {
            plugin.getLogger().info("Yuklenen tuccar NPC: 0");
            return;
        }

        int broken = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                String worldName = section.getString("world");
                if (worldName == null || worldName.isBlank()) {
                    throw new IllegalArgumentException("dunya adi bos");
                }
                register(new TraderNpc(
                        UUID.fromString(key),
                        worldName,
                        section.getDouble("x"),
                        section.getDouble("y"),
                        section.getDouble("z"),
                        (float) section.getDouble("yaw"),
                        (float) section.getDouble("pitch")));
            } catch (Exception exception) {
                broken++;
                plugin.getLogger().warning("Bozuk NPC kaydi atlandi: " + key + " (" + exception.getMessage() + ")");
            }
        }
        plugin.getLogger().info("Yuklenen tuccar NPC: " + npcs.size()
                + (broken > 0 ? " (" + broken + " bozuk kayit atlandi)" : ""));
    }

    private void markDirtyAndSave() {
        store.markDirty();
        saveAsync();
    }

    public void saveAsync() {
        store.saveAsync(snapshot());
    }

    /** Yalnizca onDisable icin (scheduler kapali). */
    public void saveNow() {
        store.saveNow(snapshot());
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration data = new YamlConfiguration();
        for (TraderNpc npc : npcs.values()) {
            String path = "npcs." + npc.getEntityId();
            data.set(path + ".world", npc.getWorldName());
            data.set(path + ".x", npc.getX());
            data.set(path + ".y", npc.getY());
            data.set(path + ".z", npc.getZ());
            data.set(path + ".yaw", npc.getYaw());
            data.set(path + ".pitch", npc.getPitch());
        }
        return data;
    }
}
