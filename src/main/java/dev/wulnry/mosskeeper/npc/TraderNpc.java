package dev.wulnry.mosskeeper.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Diske kaydedilen tuccar NPC kaydi.
 * <p>
 * Konum bilgisi {@link Location} yerine ilkel tiplerde tutulur; boylece
 * kayit, dunyasi henuz yuklenmemis olsa bile bellekte durabilir ve
 * {@link World} nesnesine gereksiz referans tutulmaz.
 */
public final class TraderNpc {

    private UUID entityId;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    /**
     * Kac kez yeniden dogurmayi denedik?
     * <p>
     * Bir baska eklenti NPC'yi surekli siliyorsa (mob temizleyici, entity
     * limiti) sonsuz dogum dongusune girmemek icin sinirlidir. Basarili bir
     * dogrulamada sifirlanir.
     */
    private int restoreAttempts;

    public TraderNpc(UUID entityId, String worldName, double x, double y, double z, float yaw, float pitch) {
        this.entityId = entityId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static TraderNpc of(UUID entityId, Location location) {
        return new TraderNpc(entityId, location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public int getRestoreAttempts() {
        return restoreAttempts;
    }

    public void countRestoreAttempt() {
        this.restoreAttempts++;
    }

    public void resetRestoreAttempts() {
        this.restoreAttempts = 0;
    }

    public int getChunkX() {
        return (int) Math.floor(x) >> 4;
    }

    public int getChunkZ() {
        return (int) Math.floor(z) >> 4;
    }

    /**
     * Chunk'i (x,z) tek bir long anahtara paketler - HashMap aramasi icin
     * nesne/String uretmeden calisir.
     */
    public long getChunkKey() {
        return chunkKey(getChunkX(), getChunkZ());
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Kayitli konumun, verilen konuma "ayni nokta" sayilacak kadar yakin olup
     * olmadigi. Yeniden dogurma yerine mevcut NPC'yi sahiplenmek icin kullanilir.
     */
    public boolean isAt(Location location, double tolerance) {
        return Math.abs(location.getX() - x) <= tolerance
                && Math.abs(location.getY() - y) <= tolerance
                && Math.abs(location.getZ() - z) <= tolerance;
    }

    /**
     * @return dunya yuklu degilse {@code null}
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }
}
