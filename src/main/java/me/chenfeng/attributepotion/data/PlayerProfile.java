package me.chenfeng.attributepotion.data;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import me.chenfeng.attributepotion.manager.ConfigManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家档案类，存储单个玩家的运行时数据。
 * <p>
 * 每个在线玩家对应一个 PlayerProfile 实例。
 */
@Getter
public class PlayerProfile {
    /**
     * 当前激活的药水实例，键为药水节点。
     */
    private final Map<String, ActivePotion> activePotions = new ConcurrentHashMap<>();
    /**
     * 药水冷却，键为药水节点，值为冷却结束的时间戳（毫秒）。
     */
    private final Map<String, Long> potionCooldowns = new ConcurrentHashMap<>();
    /**
     * 药水组冷却，键为药水组名，值为冷却结束的时间戳（毫秒）。
     */
    private final Map<String, Long> groupCooldowns = new ConcurrentHashMap<>();
    /**
     * 已从数据库读到、正在等 load-delay 恢复的药水。
     */
    private final List<ActivePotion> pendingPotions = new CopyOnWriteArrayList<>();

    /**
     * 数据库读取已返回。在此之前冷却还没有合并进来，不允许正常使用药水。
     */
    @Setter
    private volatile boolean dataLoaded;
    /**
     * 允许写回数据库。只有读取成功才为 true，否则一次读取失败就会用空档案覆盖玩家存档。
     */
    @Setter
    private volatile boolean saveAllowed;

    /**
     * 检查指定药水是否在冷却中。
     *
     * @param potionId 药水节点
     * @return 在冷却中返回 true
     */
    public boolean isPotionOnCooldown(@NonNull String potionId) {
        return isOnCooldown(potionCooldowns, potionId);
    }

    /**
     * 检查指定药水组是否在冷却中。
     *
     * @param groupName 药水组名称
     * @return 在冷却中返回 true
     */
    public boolean isGroupOnCooldown(@NonNull String groupName) {
        return isOnCooldown(groupCooldowns, groupName);
    }

    private boolean isOnCooldown(Map<String, Long> cooldowns, String key) {
        Long expireTime = cooldowns.get(key);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expireTime) {
            cooldowns.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 设置药水自身的冷却时间。
     *
     * @param potionKey 药水节点
     * @param cooldownSeconds 冷却秒数，小于等于 0 时不设置
     */
    public void setPotionCooldown(@NonNull String potionKey, double cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return;
        }
        potionCooldowns.put(potionKey, System.currentTimeMillis() + (long) (cooldownSeconds * 1000));
    }

    public double getPotionCooldownRemaining(@NonNull String potionKey) {
        return getRemainingSeconds(potionCooldowns.get(potionKey));
    }

    /**
     * 设置药水组冷却时间。fixed-group-cooldown 开启时取组配置，否则取药水自身的冷却。
     *
     * @param groupName 药水组名称
     * @param potionCooldownSeconds 药水自身的冷却秒数
     */
    public void setGroupCooldown(@NonNull String groupName, double potionCooldownSeconds) {
        double cooldownSeconds = ConfigManager.isFixedGroupCooldown()
                ? ConfigManager.getGroupCooldown(groupName)
                : potionCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return;
        }
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

    public void addActivePotion(String potionKey, ActivePotion activePotion) {
        activePotions.put(potionKey, activePotion);
    }

    public ActivePotion removeActivePotion(String potionKey) {
        return activePotions.remove(potionKey);
    }

    public void clearAllActivePotions() {
        activePotions.clear();
    }

    /**
     * 清除所有冷却时间。
     */
    public void clearAllCooldowns() {
        potionCooldowns.clear();
        groupCooldowns.clear();
    }
}
