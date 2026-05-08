package me.chenfeng.attributepotion.manager;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.api.event.PotionRemoveEvent;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.CommandHandler;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.hook.attribute.AbstractAttributeHook;
import me.chenfeng.attributepotion.manager.constructor.CommandPhase;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据管理器，负责管理所有在线玩家的 PlayerProfile。
 * <p>
 * 提供玩家数据的创建、获取、移除等操作
 * 同时负责定期检查并清理过期的药水效果。
 */
public class PlayerManager {
    private static final Map<UUID, PlayerProfile> playerProfileMap = new ConcurrentHashMap<>();
    private static BukkitTask expirationCheckTask;

    private PlayerManager() {}

    /**
     * 启动药水过期检查任务。
     * <p>
     * 该方法应在插件 onEnable 阶段调用，启动一个周期性任务，
     * 每隔一定时间检查所有在线玩家的激活药水是否过期，并自动清理。
     * 
     * @param checkIntervalTicks 检查间隔（游戏刻），建议 20 tick（1秒）
     */
    public static void startExpirationCheckTask(int checkIntervalTicks) {
        if (expirationCheckTask != null && !expirationCheckTask.isCancelled()) {
            return;
        }

        expirationCheckTask = Bukkit.getScheduler().runTaskTimer(
            AttributePotion.getInstance(),
                PlayerManager::checkAndRemoveExpiredPotions,
            checkIntervalTicks,
            checkIntervalTicks
        );
    }

    /**
     * 停止药水过期检查任务。
     * <p>
     * 该方法应在插件 onDisable 阶段调用，确保资源正确释放。
     */
    public static void stopExpirationCheckTask() {
        if (expirationCheckTask != null && !expirationCheckTask.isCancelled()) {
            expirationCheckTask.cancel();
            expirationCheckTask = null;
        }
    }

    /**
     * 检查并移除所有玩家已过期的药水。
     * <p>
     * 遍历所有在线玩家的激活药水列表，对于已过期的药水：
     * 1. 执行 END 阶段命令
     * 2. 移除应用的属性
     * 3. 取消 tick 任务
     * 4. 从激活列表中移除
     */
    private static void checkAndRemoveExpiredPotions() {
        AbstractAttributeHook attributeHook = AttributePotion.getInstance().getAttributeHook();
        
        for (Map.Entry<UUID, PlayerProfile> entry : playerProfileMap.entrySet()) {
            PlayerProfile profile = entry.getValue();
            UUID playerUuid = entry.getKey();
            Player player = Bukkit.getPlayer(playerUuid);
            
            if (player == null || !player.isOnline()) {
                continue;
            }

            Map<String, ActivePotion> activePotions = profile.getActivePotions();
            List<String> expiredKeys = new ArrayList<>();

            for (Map.Entry<String, ActivePotion> potionEntry : activePotions.entrySet()) {
                String potionKey = potionEntry.getKey();
                ActivePotion activePotion = potionEntry.getValue();

                if (activePotion.isExpired()) {
                    expiredKeys.add(potionKey);
                }
            }

            for (String potionKey : expiredKeys) {
                removeExpiredPotion(player, profile, potionKey, attributeHook);
            }
        }
    }

    /**
     * 移除单个过期药水并执行清理操作。
     * 
     * @param player 玩家对象
     * @param profile 玩家档案
     * @param potionKey 药水配置键名
     * @param attributeHook 属性系统适配器
     */
    private static void removeExpiredPotion(Player player, PlayerProfile profile, 
                                           String potionKey, AbstractAttributeHook attributeHook) {
        removeActivePotion(player, profile, potionKey, attributeHook);
    }

    public static void removeActivePotion(Player player, PlayerProfile profile, String potionKey) {
        removeActivePotion(player, profile, potionKey, AttributePotion.getInstance().getAttributeHook());
    }

    private static void removeActivePotion(Player player, PlayerProfile profile,
                                           String potionKey, AbstractAttributeHook attributeHook) {
        ActivePotion activePotion = profile.removeActivePotion(potionKey);

        if (activePotion == null) {
            return;
        }

        PotionConfig config = ConfigManager.getPotionConfig(potionKey);

        try {
            activePotion.deactivate();

            if (attributeHook != null && !activePotion.getAppliedAttributes().isEmpty()) {
                attributeHook.takeAttribute(player, potionKey);
            }

            if (config != null) {
                executeCommandPhase(player, config, CommandPhase.END);
                PotionRemoveEvent.callEvent(player, config, activePotion);
            }

            if (ConfigManager.isDebug()) {
                LoggerUtil.debug("[AttributePotion] Player " + player.getName() + " potion " + potionKey + " removed");
            }
        } catch (Exception e) {
            LoggerUtil.warning("[AttributePotion] Failed to remove potion " + potionKey + ": " + e.getMessage());
        }
    }

    /**
     * 执行指定阶段的命令。
     * 
     * @param player 玩家对象
     * @param config 药水配置
     * @param phase 命令阶段
     */
    private static void executeCommandPhase(Player player, PotionConfig config, CommandPhase phase) {
        CommandHandler.executeCommands(player, config.getCommands().get(phase), config.getTime());
    }

    public static PlayerProfile getOrCreateProfile(Player player) {
        return playerProfileMap.computeIfAbsent(player.getUniqueId(),
            uuid -> new PlayerProfile());
    }

    public static PlayerProfile getProfile(UUID uuid) {
        return playerProfileMap.get(uuid);
    }

    public static PlayerProfile getProfile(Player player) {
        return getProfile(player.getUniqueId());
    }

    public static PlayerProfile removeProfile(UUID uuid) {
        PlayerProfile profile = playerProfileMap.remove(uuid);
        
        if (profile != null) {
            cleanupAllPotions(profile, uuid);
        }
        
        return profile;
    }

    public static PlayerProfile removeProfile(Player player) {
        return removeProfile(player.getUniqueId());
    }

    public static void restoreActivePotion(Player player, PlayerProfile profile, ActivePotion activePotion) {
        if (activePotion == null || activePotion.isExpired()) {
            return;
        }
        profile.addActivePotion(activePotion.getPotionKey(), activePotion);
        PotionHandler.restoreActivePotion(player, activePotion);
    }

    public static PlayerProfile unloadProfile(UUID uuid) {
        PlayerProfile profile = playerProfileMap.remove(uuid);
        if (profile != null) {
            for (ActivePotion activePotion : profile.getActivePotions().values()) {
                activePotion.deactivate();
            }
            profile.clearAllActivePotions();
        }
        return profile;
    }

    /**
     * 清理玩家的所有激活药水（通常在玩家退出时调用）。
     * 
     * @param profile 玩家档案
     * @param playerUuid 玩家 UUID
     */
    private static void cleanupAllPotions(PlayerProfile profile, UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);

        for (String potionKey : new ArrayList<>(profile.getActivePotions().keySet())) {
            if (player != null) {
                removeActivePotion(player, profile, potionKey);
            } else {
                ActivePotion activePotion = profile.removeActivePotion(potionKey);
                if (activePotion != null) {
                    activePotion.deactivate();
                }
            }
        }

        profile.clearAllActivePotions();
    }

    public static boolean hasProfile(UUID uuid) {
        return playerProfileMap.containsKey(uuid);
    }

    public static Map<UUID, PlayerProfile> getAllProfiles() {
        return new ConcurrentHashMap<>(playerProfileMap);
    }

    public static void clearAllProfiles() {
        for (Map.Entry<UUID, PlayerProfile> entry : new ArrayList<>(playerProfileMap.entrySet())) {
            cleanupAllPotions(entry.getValue(), entry.getKey());
        }
        playerProfileMap.clear();
    }

    public static int getProfileCount() {
        return playerProfileMap.size();
    }
}
