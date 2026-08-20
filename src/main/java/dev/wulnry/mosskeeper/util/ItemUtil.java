package dev.wulnry.mosskeeper.util;

import org.bukkit.ChatColor;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Esya serilestirme ve envanter yardimcilari.
 * <p>
 * NBT (buyu, ozel isim, lore, custom model data, PDC ...) kaybi yasanmamasi
 * icin {@link BukkitObjectOutputStream} + Base64 kullanilir. Bu yontem
 * Bukkit'in resmi serilestirme zinciridir; kaydedilen veri sunucu surumunu de
 * icerdiginden surum yukseltmelerinde DataFixer ile geri okunabilir.
 *
 * <h3>Odeme hangi slotlardan alinir?</h3>
 * Ana envanter (0-35, hotbar dahil) <b>ve yan el</b>. Zirh slotlari kasten
 * disarida birakilmistir: oyuncunun uzerindeki zirh yanlislikla odeme olarak
 * alinmamalidir.
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    public static String encode(ItemStack item) {
        if (item == null) {
            return null;
        }
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream objectStream = new BukkitObjectOutputStream(byteStream)) {
            objectStream.writeObject(item);
            objectStream.flush();
            return Base64.getEncoder().encodeToString(byteStream.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("Esya serilestirilemedi: " + item.getType(), exception);
        }
    }

    public static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(data);
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream objectStream = new BukkitObjectInputStream(byteStream)) {
            return (ItemStack) objectStream.readObject();
        } catch (Exception exception) {
            throw new IllegalStateException("Esya okunamadi (bozuk veri).", exception);
        }
    }

    /**
     * Esyanin okunabilir adi: ozel isim varsa o, yoksa materyal adi.
     */
    public static String displayName(ItemStack item) {
        if (item == null) {
            return "-";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        return TextUtil.prettify(item.getType().name());
    }

    /**
     * Envanterde, verilen sablona benzeyen (NBT dahil) toplam esya adedi.
     */
    public static int count(Inventory inventory, ItemStack template) {
        int total = 0;
        for (ItemStack content : inventory.getStorageContents()) {
            if (content != null && content.isSimilar(template)) {
                total = saturatedAdd(total, content.getAmount());
            }
        }
        ItemStack offHand = offHand(inventory);
        if (offHand != null && offHand.isSimilar(template)) {
            total = saturatedAdd(total, offHand.getAmount());
        }
        return total;
    }

    /**
     * Envanterden, sablona benzeyen esyalardan tam olarak {@code amount} adet siler.
     *
     * @return silinemeyen (eksik kalan) miktar; 0 ise islem tamdir.
     */
    public static int remove(Inventory inventory, ItemStack template, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack content = contents[slot];
            if (content == null || !content.isSimilar(template)) {
                continue;
            }
            int taken = Math.min(remaining, content.getAmount());
            remaining -= taken;
            if (content.getAmount() - taken <= 0) {
                contents[slot] = null;
            } else {
                content.setAmount(content.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);

        if (remaining > 0 && inventory instanceof PlayerInventory playerInventory) {
            remaining = removeFromOffHand(playerInventory, template, remaining);
        }
        return remaining;
    }

    private static int removeFromOffHand(PlayerInventory inventory, ItemStack template, int remaining) {
        ItemStack offHand = inventory.getItemInOffHand();
        if (offHand == null || !offHand.isSimilar(template)) {
            return remaining;
        }
        int taken = Math.min(remaining, offHand.getAmount());
        int left = offHand.getAmount() - taken;
        if (left <= 0) {
            inventory.setItemInOffHand(null);
        } else {
            // getItemInOffHand() canli bir aynayi dondurebilir; kopya uzerinden
            // yazmak her sunucu yaziliminda ayni davranisi garanti eder.
            ItemStack copy = offHand.clone();
            copy.setAmount(left);
            inventory.setItemInOffHand(copy);
        }
        return remaining - taken;
    }

    /**
     * Verilen sablondan envantere en fazla kac <b>adet</b> eklenebilir?
     * <p>
     * Tek gecistir (O(slot)). "Sigiyor mu?" sorusunu tekrar tekrar sorup
     * miktari birer birer azaltan dongulere gerek birakmaz - toplu takasta
     * dogru adet tek hesapla bulunur.
     */
    public static int capacityFor(Inventory inventory, ItemStack item) {
        int maxStack = Math.max(1, item.getMaxStackSize());
        int capacity = 0;
        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                capacity = saturatedAdd(capacity, maxStack);
            } else if (content.isSimilar(item)) {
                capacity = saturatedAdd(capacity, Math.max(0, content.getMaxStackSize() - content.getAmount()));
            }
        }
        return capacity;
    }

    /**
     * {@code total} adet esyayi, max stack boyutuna gore parcalara ayirir.
     */
    public static List<ItemStack> split(ItemStack template, int total) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>((total / maxStack) + 1);
        int remaining = total;
        while (remaining > 0) {
            ItemStack copy = template.clone();
            int size = Math.min(maxStack, remaining);
            copy.setAmount(size);
            stacks.add(copy);
            remaining -= size;
        }
        return stacks;
    }

    private static ItemStack offHand(Inventory inventory) {
        return inventory instanceof PlayerInventory playerInventory
                ? playerInventory.getItemInOffHand()
                : null;
    }

    /** Tasma halinde {@link Integer#MAX_VALUE}'da doyar. */
    private static int saturatedAdd(int base, int addition) {
        int sum = base + addition;
        return sum < base ? Integer.MAX_VALUE : sum;
    }
}
