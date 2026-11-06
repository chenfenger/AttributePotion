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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据管理器，负责管理所有在线玩家的 PlayerProfile，并定期清理过期药水。
 * <p>
 * 移除药水分两种语义，不要混用：
 * <ul>
 *   <li>{@link #removeActivePotion}：药水真正结束，会执行 end 动作并触发 {@link PotionRemoveEvent}。</li>
 *   <li>{@link #unloadProfile}：玩家下线或插件关闭，数据已存库、之后还会恢复，只撤销运行时状态，不执行 end 动作。</li>
 * </ul>
 */
public class PlayerManager {
    private static final Map<UUID, PlayerProfile> playerProfileMap = new ConcurrentHashMap<>();
    private static BukkitTask expirationCheckTask;

    private PlayerManager() {}

    /**
     * 启动药水过期检查任务。
     *
     * @param checkIntervalTicks 检查间隔（游戏刻）
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
     */
    public static void stopExpirationCheckTask() {
        if (expirationCheckTask != null && !expirationCheckTask.isCancelled()) {
            expirationCheckTask.cancel();
            expirationCheckTask = null;
        }
    }

    private static void checkAndRemoveExpiredPotions() {
        for (Map.Entry<UUID, PlayerProfile> entry : playerProfileMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                removeExpiredPotions(player, entry.getValue());
            }
        }
    }

    /**
     * 立即结算玩家所有已到期的药水，执行 end 动作并发送结束消息。
     *
     * @param player 玩家
     * @param profile 玩家档案
     */
    public static void removeExpiredPotions(Player player, PlayerProfile profile) {
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, ActivePotion> entry : profile.getActivePotions().entrySet()) {
            if (entry.getValue().isExpired()) {
                expiredKeys.add(entry.getKey());
            }
        }

        for (String potionKey : expiredKeys) {
            ActivePotion removed = removePotion(player, profile, potionKey);
            if (removed != null) {
                sendEndMessage(player, potionKey);
            }
        }
    }

    /**
     * 结束一个激活中的药水：撤销属性和原版效果，执行 end 动作并触发移除事件。
     *
     * @param player 玩家
     * @param profile 玩家档案
     * @param potionKey 药水节点
     */
    public static void removeActivePotion(Player player, PlayerProfile profile, String potionKey) {
        removePotion(player, profile, potionKey);
    }

    private static ActivePotion removePotion(Player player, PlayerProfile profile, String potionKey) {
        ActivePotion activePotion = profile.removeActivePotion(potionKey);
        if (activePotion == null) {
            return null;
        }

        activePotion.deactivate();
        takeAttributes(player, activePotion);

        PotionConfig config = ConfigManager.getPotionConfig(potionKey);
        if (config != null) {
            PotionHandler.removeVanillaEffects(player, config);
            executeCommandPhase(player, config, CommandPhase.END);
            PotionRemoveEvent.callEvent(player, config, activePotion);
        }

        LoggerUtil.debug("[AttributePotion] Player " + player.getName() + " potion " + potionKey + " removed");
        return activePotion;
    }

    /**
     * 结算玩家离线期间到期的药水。属性和原版效果在离线时已经不存在，只补发结束动作。
     *
     * @param player 玩家
     * @param activePotion 离线期间到期的药水
     */
    public static void finishOfflineExpiredPotion(Player player, ActivePotion activePotion) {
        PotionConfig config = ConfigManager.getPotionConfig(activePotion.getPotionKey());
        if (config == null || !ConfigManager.isRunEndOnOfflineExpire()) {
            return;
        }

        sendEndMessage(player, activePotion.getPotionKey());
        executeCommandPhase(player, config, CommandPhase.END);
        PotionRemoveEvent.callEvent(player, config, activePotion);
    }

    private static void sendEndMessage(Player player, String potionKey) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("%id%", potionKey);
        replacements.put("%potion%", potionKey);
        ConfigManager.sendMessage(player, "out-potion", replacements);
    }

    private static void takeAttributes(Player player, ActivePotion activePotion) {
        AbstractAttributeHook attributeHook = AttributePotion.getInstance().getAttributeHook();
        if (attributeHook == null || player == null || activePotion.getAppliedAttributes().isEmpty()) {
            return;
        }

        try {
            attributeHook.takeAttribute(player, activePotion.getPotionKey());
        } catch (Exception | LinkageError e) {
            LoggerUtil.warning("[AttributePotion] Failed to take attributes of potion "
                    + activePotion.getPotionKey() + ": " + e);
        }
    }

    private static void executeCommandPhase(Player player, PotionConfig config, CommandPhase phase) {
        CommandHandler.executeCommands(player, config.getCommands().get(phase), config.getTime());
    }

    public static PlayerProfile getOrCreateProfile(Player player) {
        return playerProfileMap.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerProfile());
    }

    public static PlayerProfile getProfile(UUID uuid) {
        return playerProfileMap.get(uuid);
    }

    public static PlayerProfile getProfile(Player player) {
        return getProfile(player.getUniqueId());
    }

    /**
     * 恢复一个尚未到期的药水到玩家身上。
     */
    public static void restoreActivePotion(Player player, PlayerProfile profile, ActivePotion activePotion) {
        if (activePotion == null || activePotion.isExpired()) {
            return;
        }
        profile.addActivePotion(activePotion.getPotionKey(), activePotion);
        PotionHandler.restoreActivePotion(player, activePotion);
    }

    /**
     * 卸载玩家档案：取消定时任务并撤销属性，不执行 end 动作。
     * <p>
     * 调用前应先把档案存库，否则未结束的药水会丢失。
     *
     * @param uuid 玩家 UUID
     * @return 被卸载的档案，不存在时返回 null
     */
    public static PlayerProfile unloadProfile(UUID uuid) {
        PlayerProfile profile = playerProfileMap.remove(uuid);
        if (profile == null) {
            return null;
        }

        Player player = Bukkit.getPlayer(uuid);
        for (ActivePotion activePotion : profile.getActivePotions().values()) {
            activePotion.deactivate();
            takeAttributes(player, activePotion);
        }
        profile.clearAllActivePotions();
        return profile;
    }

    /**
     * 卸载所有在线玩家的档案，用于插件关闭。
     */
    public static void unloadAllProfiles() {
        for (UUID uuid : new ArrayList<>(playerProfileMap.keySet())) {
            unloadProfile(uuid);
        }
    }

    public static Map<UUID, PlayerProfile> getAllProfiles() {
        return Collections.unmodifiableMap(new HashMap<>(playerProfileMap));
    }
}
