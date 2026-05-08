package me.chenfeng.attributepotion.data;

import lombok.Getter;
import lombok.NonNull;
import me.chenfeng.attributepotion.manager.ConfigManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家档案类，存储单个玩家的运行时数据。
 * <p>
 * 包含玩家当前激活的药水实例和药水组冷却时间等信息。
 * 每个在线玩家对应一个 PlayerProfile 实例。
 */
@Getter
public class PlayerProfile {
    /**
     * 玩家当前激活的药水实例映射。
     * <p>
     * 键为药水配置键名，值为激活的药水实例。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, ActivePotion> activePotions;
    private final Map<String, Long> potionCooldowns;
    
    /**
     * 药水组冷却时间映射。
     * <p>
     * 键为药水组名称，值为下次可使用的时间戳（毫秒）。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, Long> groupCooldowns;

    /**
     * 构造空的玩家档案。
     */
    public PlayerProfile() {
        this.activePotions = new ConcurrentHashMap<>();
        this.potionCooldowns = new ConcurrentHashMap<>();
        this.groupCooldowns = new ConcurrentHashMap<>();
    }

    /**
     * 检查指定药水是否在冷却中。
     * 
     * @param potionId 药水配置键名
     * @return 如果药水在冷却中返回 true，否则返回 false
     */
    public boolean isPotionOnCooldown(@NonNull String potionId) {
        Long expireTime = potionCooldowns.get(potionId);
        if (expireTime == null) {
            return false;
        }

        if (System.currentTimeMillis() >= expireTime) {
            potionCooldowns.remove(potionId);
            return false;
        }

        return true;
    }

    /**
     * 检查指定药水组是否在冷却中。
     * 
     * @param groupName 药水组名称
     * @return 如果药水组在冷却中返回 true，否则返回 false
     */
    public boolean isGroupOnCooldown(@NonNull String groupName) {
        Long expireTime = groupCooldowns.get(groupName);
        if (expireTime == null) {
            return false;
        }

        if (System.currentTimeMillis() >= expireTime) {
            groupCooldowns.remove(groupName);
            return false;
        }

        return true;
    }

    /**
     * 设置药水组的冷却时间。
     * 
     * @param groupName 药水组名称
     */
    public void setPotionCooldown(@NonNull String potionKey, double cooldownSeconds) {
        if (cooldownSeconds <= 0) return;
        potionCooldowns.put(potionKey, System.currentTimeMillis() + (long) (cooldownSeconds * 1000));
    }

    public double getPotionCooldownRemaining(@NonNull String potionKey) {
        return getRemainingSeconds(potionCooldowns.get(potionKey));
    }

    public void setGroupCooldown(@NonNull String groupName, double potionCooldownSeconds) {
        double cooldownSeconds = ConfigManager.isFixedGroupCooldown()
                ? ConfigManager.getGroupCooldown(groupName)
                : potionCooldownSeconds;
        if(cooldownSeconds <= 0) return;
        groupCooldowns.put(groupName, System.currentTimeMillis() + (long) (cooldownSeconds * 1000));
    }

    public double getGroupCooldownRemaining(@NonNull String groupName) {
        return getRemainingSeconds(groupCooldowns.get(groupName));
    }

    private double getRemainingSeconds(Long expireTime) {
        if (expireTime == null) {
            return 0;
        }
        long remainingMillis = expireTime - System.currentTimeMillis();
        return Math.max(0, remainingMillis / 1000.0);
    }

    public void addActivePotion(String potionKey) {
        PotionConfig potionData = ConfigManager.getPotionConfig(potionKey);
        if (potionData == null) {
            return;
        }
        //addActivePotion(potionKey, new ActivePotion(potionKey));
    }

    /**
     * 添加激活的药水实例。
     * 
     * @param potionKey 药水配置键名
     * @param activePotion 激活的药水实例
     */
    public void addActivePotion(String potionKey, ActivePotion activePotion) {
        activePotions.put(potionKey, activePotion);
    }

    /**
     * 移除激活的药水实例。
     * 
     * @param potionKey 药水配置键名
     * @return 被移除的药水实例，如果不存在则返回 null
     */
    public ActivePotion removeActivePotion(String potionKey) {
        return activePotions.remove(potionKey);
    }

    /**
     * 清除所有激活的药水（通常在玩家退出时调用）。
     */
    public void clearAllActivePotions() {
        activePotions.clear();
    }

    /**
     * 清除所有冷却时间（通常在管理员命令中使用）。
     */
    public void clearAllCooldowns() {
        potionCooldowns.clear();
        groupCooldowns.clear();
    }
}
