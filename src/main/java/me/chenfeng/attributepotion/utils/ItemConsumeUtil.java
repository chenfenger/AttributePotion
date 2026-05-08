package me.chenfeng.attributepotion.utils;

import de.tr7zw.nbtapi.NBT;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.function.Function;

/**
 * 药水物品消耗工具。
 * <p>
 * 当物品带有配置的次数 NBT 时，优先扣除 NBT 次数；没有次数 NBT 时按普通物品数量消耗。
 */
public final class ItemConsumeUtil {
    private ItemConsumeUtil() {
    }

    /**
     * 消耗一个药水物品，并返回消耗后的物品。
     *
     * @param itemStack 原始物品
     * @return 消耗后的物品；返回 null 表示物品应被移除
     */
    public static ItemStack consumeOne(ItemStack itemStack, PotionConfig config) {
        if (ItemUtil.isAir(itemStack)) {
            return itemStack;
        }

        ItemStack countedItem = consumeNbtCount(itemStack, config);
        if (countedItem != null) {
            return countedItem.getAmount() <= 0 ? null : countedItem;
        }

        if (itemStack.getAmount() <= 1) {
            return null;
        }

        itemStack.setAmount(itemStack.getAmount() - 1);
        return itemStack;
    }

    /**
     * 消耗玩家指定手上的一个药水物品。
     *
     * @param player 玩家
     * @param hand 装备槽位
     * @param itemStack 被消耗的物品
     */
    public static void consumeHand(Player player, EquipmentSlot hand, ItemStack itemStack, PotionConfig config) {
        ItemStack updatedItem = consumeOne(itemStack, config);
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(updatedItem);
        } else {
            player.getInventory().setItemInMainHand(updatedItem);
        }
    }

    /**
     * 判断物品是否存在可扣除的次数 NBT。
     *
     * @param itemStack 物品
     * @return 存在次数 NBT 且次数大于 0 时返回 true
     */
    public static boolean hasNbtCount(ItemStack itemStack) {
        String key = ConfigManager.getNbtCountKey();
        if (!ConfigManager.isNbtApiAvailable() || ItemUtil.isAir(itemStack) || key == null || key.trim().isEmpty()) {
            return false;
        }

        return NBT.get(itemStack, nbt -> {
            Integer count = nbt.getInteger(key);
            return count != null && count > 0;
        });
    }

    /**
     * 判断物品当前是否允许通过 NBT 次数使用。
     *
     * @param itemStack 物品
     * @return 没有次数 NBT 或次数大于 0 时返回 true
     */
    public static boolean canUseByNbtCount(ItemStack itemStack) {
        String key = ConfigManager.getNbtCountKey();
        if (!ConfigManager.isNbtApiAvailable() || ItemUtil.isAir(itemStack) || key == null || key.trim().isEmpty()) {
            return true;
        }

        return NBT.get(itemStack, nbt -> {
            if (!nbt.hasTag(key)) {
                return true;
            }
            Integer count = nbt.getInteger(key);
            return count != null && count > 0;
        });
    }

    /**
     * 扣除物品的次数 NBT。
     *
     * @param itemStack 原始物品
     * @return 处理后的物品；如果没有次数 NBT 返回 null
     */
    private static ItemStack consumeNbtCount(ItemStack itemStack, PotionConfig config) {
        String key = ConfigManager.getNbtCountKey();
        if (!ConfigManager.isNbtApiAvailable() || key == null || key.trim().isEmpty()) {
            return null;
        }

        boolean removeWhenEmpty = config != null && config.getOptional().isEnabled(OptionKey.BREAK, false);
        Boolean handled = NBT.modify(itemStack, nbt -> {
            Integer count = nbt.getInteger(key);
            if (count == null || count <= 0) {
                return false;
            }

            if (count <= 1) {
                if (removeWhenEmpty) {
                    nbt.removeKey(key);
                } else {
                    nbt.setInteger(key, 0);
                }
            } else {
                nbt.setInteger(key, count - 1);
            }
            return true;
        });

        if (!Boolean.TRUE.equals(handled)) {
            return null;
        }

        Function<de.tr7zw.nbtapi.iface.ReadableItemNBT, Boolean> hasCountKey = nbt -> nbt.hasTag(key);
        if (NBT.get(itemStack, hasCountKey)) {
            return itemStack;
        }
        return removeWhenEmpty ? null : itemStack;
    }
}
