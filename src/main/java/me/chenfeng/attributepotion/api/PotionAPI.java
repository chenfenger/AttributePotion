package me.chenfeng.attributepotion.api;

import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Map;

/**
 * 提供给其他插件调用的属性药水公共 API 门面。
 */
public final class PotionAPI {
    /**
     * 工具类，不需要创建实例。
     */
    private PotionAPI() {
    }

    /**
     * 匹配指定物品并按正常流程使用匹配到的药水。
     *
     * @param player 使用物品的玩家
     * @param itemStack 需要匹配的物品
     * @return 匹配到药水并成功应用时返回 true
     */
    public static boolean usePotion(Player player, ItemStack itemStack) {
        if (player == null || itemStack == null) {
            return false;
        }
        return PotionHandler.usePotion(player, itemStack);
    }

    /**
     * 按药水节点使用药水，执行完整检查流程。
     *
     * @param player 使用药水的玩家
     * @param potionKey 药水配置节点
     * @return 药水成功应用时返回 true
     */
    public static boolean usePotion(Player player, String potionKey) {
        if (player == null || potionKey == null) {
            return false;
        }
        return PotionHandler.usePotion(player, potionKey);
    }

    /**
     * 按药水节点强制使用药水，跳过可选条件、冷却、使用条件和使用事件。
     *
     * @param player 目标玩家
     * @param potionKey 药水配置节点
     * @return 药水存在并成功应用时返回 true
     */
    public static boolean forceUsePotion(Player player, String potionKey) {
        if (player == null || potionKey == null) {
            return false;
        }
        return PotionHandler.forceUsePotion(player, potionKey);
    }

    /**
     * 给玩家身上已激活的非永久药水增减持续时间。
     *
     * @param player 目标玩家
     * @param potionKey 已激活的药水节点
     * @param seconds 增加的秒数，负数表示减少剩余时间
     * @return 成功修改已激活药水时返回 true
     */
    public static boolean addPotionTime(Player player, String potionKey, double seconds) {
        if (player == null || potionKey == null) {
            return false;
        }
        return PotionHandler.addPotionTime(player, potionKey, seconds);
    }

    /**
     * 移除玩家身上的指定激活药水，并执行正常清理逻辑。
     *
     * @param player 目标玩家
     * @param potionKey 已激活的药水节点
     * @return 玩家拥有该药水并成功移除时返回 true
     */
    public static boolean removePotion(Player player, String potionKey) {
        if (player == null || potionKey == null) {
            return false;
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile == null || !profile.getActivePotions().containsKey(potionKey)) {
            return false;
        }

        PlayerManager.removeActivePotion(player, profile, potionKey);
        return true;
    }

    /**
     * 检查玩家当前是否拥有指定激活药水。
     *
     * @param player 目标玩家
     * @param potionKey 需要检查的药水节点
     * @return 药水处于激活状态时返回 true
     */
    public static boolean hasPotion(Player player, String potionKey) {
        if (player == null || potionKey == null) {
            return false;
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        return profile != null && profile.getActivePotions().containsKey(potionKey);
    }

    /**
     * 获取指定激活药水的剩余时间，单位为秒。
     *
     * @param player 目标玩家
     * @param potionKey 已激活的药水节点
     * @return 剩余秒数，永久药水返回 -1，不存在返回 0
     */
    public static double getRemainingTime(Player player, String potionKey) {
        ActivePotion activePotion = getActivePotion(player, potionKey);
        return activePotion == null ? 0 : activePotion.getRemainingSeconds();
    }

    /**
     * 获取运行时激活药水对象，供高级集成使用。
     *
     * @param player 目标玩家
     * @param potionKey 已激活的药水节点
     * @return 激活药水实例，不存在时返回 null
     */
    public static ActivePotion getActivePotion(Player player, String potionKey) {
        if (player == null || potionKey == null) {
            return null;
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        return profile == null ? null : profile.getActivePotions().get(potionKey);
    }

    /**
     * 根据节点获取已加载的药水配置。
     *
     * @param potionKey 药水配置节点
     * @return 药水配置，不存在时返回 null
     */
    public static PotionConfig getPotionConfig(String potionKey) {
        return potionKey == null ? null : ConfigManager.getPotionConfig(potionKey);
    }

    /**
     * 获取所有已加载的药水配置。
     *
     * @return 药水节点到配置对象的不可变映射
     */
    public static Map<String, PotionConfig> getAllPotionConfigs() {
        return Collections.unmodifiableMap(ConfigManager.getAllPotionData());
    }
}
