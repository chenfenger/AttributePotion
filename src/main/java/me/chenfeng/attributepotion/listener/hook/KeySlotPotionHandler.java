package me.chenfeng.attributepotion.listener.hook;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import me.chenfeng.attributepotion.utils.ItemConsumeUtil;
import me.chenfeng.attributepotion.utils.ItemUtil;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 按键槽位药水的公共流程，龙核、萌芽、云拾、ArcartX 都走这里。
 * <p>
 * 槽位物品是异步读写的，这里遵守两条规则，否则会刷物品或吞物品：
 * <ul>
 *   <li>扣除前必须重新读取槽位。蓄力期间玩家可能已经把物品拿走或换掉，不能把蓄力开始时读到的物品写回去。</li>
 *   <li>一次“读取、使用、写回”完成前，同一玩家不开始下一次，否则第二次会读到写回前的旧数量。</li>
 * </ul>
 * 按键事件可能不在主线程触发，所有药水逻辑都切回主线程执行。
 */
public final class KeySlotPotionHandler {
    private static final long PRESS_UPDATE_INTERVAL = 1L;
    /**
     * 写回操作一直没有回调时，超过这个时间自动解锁，避免玩家的按键永久失效。
     */
    private static final long BUSY_TIMEOUT_MILLIS = 5000L;
    private static final Map<UUID, Long> BUSY_UNTIL = new ConcurrentHashMap<>();

    private KeySlotPotionHandler() {}

    /**
     * 蓄力进度显示。
     */
    interface PressDisplayReporter {
        void report(Player player, String key, PotionConfig config, double progress);

        void stop(Player player);
    }

    /**
     * 一个玩家的一个按键槽位。不支持异步的槽位 API 用 {@link CompletableFuture#completedFuture} 包装。
     */
    interface SlotAccess {
        CompletableFuture<ItemStack> read();

        /**
         * @param itemStack 新物品，null 表示清空槽位
         */
        CompletableFuture<?> write(ItemStack itemStack);
    }

    static String resolveSlot(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return null;
        }

        String slot = ConfigManager.getSlot(rawKey);
        if (slot != null) {
            return slot;
        }

        String normalized = normalizeKey(rawKey);
        slot = ConfigManager.getSlot(normalized);
        if (slot != null) {
            return slot;
        }

        return ConfigManager.getSlot("KEY_" + normalized);
    }

    /**
     * 处理按键按下。
     */
    static void onKeyPress(Player player, String rawKey, SlotAccess slot, PressDisplayReporter reporter) {
        if (!KeyPressTracker.record(player, rawKey)) {
            return;
        }
        if (!tryLock(player)) {
            KeyPressTracker.clear(player, rawKey);
            return;
        }

        readThen(player, slot, () -> {
            unlock(player);
            interrupt(player, rawKey, reporter);
        }, itemStack -> {
            boolean writePending = false;
            try {
                writePending = handlePress(player, rawKey, itemStack, slot, reporter);
            } finally {
                if (!writePending) {
                    unlock(player);
                }
            }
        });
    }

    /**
     * 处理按键松开。
     */
    static void onKeyRelease(Player player, String rawKey, SlotAccess slot, PressDisplayReporter reporter) {
        if (!KeyPressTracker.hasState(player, rawKey)) {
            return;
        }
        readThen(player, slot, () -> interrupt(player, rawKey, reporter),
                itemStack -> handleRelease(player, rawKey, itemStack, slot, reporter));
    }

    /**
     * @return 是否有写回操作进行中（写回完成时才解锁）
     */
    private static boolean handlePress(Player player, String rawKey, ItemStack itemStack, SlotAccess slot, PressDisplayReporter reporter) {
        PotionConfig config = ItemUtil.isAir(itemStack) ? null : PotionHandler.matchPotion(itemStack);
        if (config == null) {
            interrupt(player, rawKey, reporter);
            return false;
        }

        if (config.getPress() <= 0) {
            KeyPressTracker.clear(player, rawKey);
            return useAndConsume(player, itemStack, config, slot);
        }

        if (!ItemConsumeUtil.canUseByNbtCount(itemStack) || !PotionHandler.canUsePotion(player, config, TriggerType.KEY)) {
            interrupt(player, rawKey, reporter);
            return false;
        }

        KeyPressTracker.cancelTask(player, rawKey);
        reportProgress(player, rawKey, config, reporter);
        startPressTask(player, rawKey, slot, config, reporter);
        return false;
    }

    private static void handleRelease(Player player, String rawKey, ItemStack itemStack, SlotAccess slot, PressDisplayReporter reporter) {
        PotionConfig config = ItemUtil.isAir(itemStack) ? null : PotionHandler.matchPotion(itemStack);
        if (config == null || config.getPress() <= 0) {
            interrupt(player, rawKey, reporter);
            return;
        }

        KeyPressTracker.cancelTask(player, rawKey);
        if (KeyPressTracker.releaseSeconds(player, rawKey) < config.getPress()) {
            reportProgress(player, rawKey, config, reporter);
            startDecayTask(player, rawKey, config, reporter);
            return;
        }
        fire(player, rawKey, slot, config, reporter);
    }

    /**
     * 蓄力完成：重新读取槽位，确认物品还是这瓶药水后才使用并扣除。
     */
    private static void fire(Player player, String rawKey, SlotAccess slot, PotionConfig config, PressDisplayReporter reporter) {
        if (!KeyPressTracker.markFired(player, rawKey)) {
            return;
        }
        if (!tryLock(player)) {
            interrupt(player, rawKey, reporter);
            return;
        }

        readThen(player, slot, () -> {
            unlock(player);
            interrupt(player, rawKey, reporter);
        }, itemStack -> {
            boolean writePending = false;
            try {
                PotionConfig current = ItemUtil.isAir(itemStack) ? null : PotionHandler.matchPotion(itemStack);
                if (current != null && current.getKey().equals(config.getKey())) {
                    writePending = useAndConsume(player, itemStack, current, slot);
                }
            } finally {
                if (!writePending) {
                    unlock(player);
                }
                interrupt(player, rawKey, reporter);
            }
        });
    }

    /**
     * 使用药水并扣除槽位物品。
     *
     * @return 是否有写回操作进行中
     */
    private static boolean useAndConsume(Player player, ItemStack itemStack, PotionConfig config, SlotAccess slot) {
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)
                || !PotionHandler.usePotion(player, itemStack, config, TriggerType.KEY)
                || !config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
            return false;
        }

        slot.write(ItemConsumeUtil.consumeOne(itemStack, config)).whenComplete((ignored, error) -> {
            unlock(player);
            if (error != null) {
                LoggerUtil.warning("[AttributePotion] 写入按键槽位失败: " + error);
            }
        });
        return true;
    }

    /**
     * 读取槽位物品，然后回到主线程处理。玩家已下线或重新登录时走 onAbort。
     */
    private static void readThen(Player player, SlotAccess slot, Runnable onAbort, Consumer<ItemStack> callback) {
        runSync(() -> slot.read().whenComplete((itemStack, error) -> runSync(() -> {
            if (error != null) {
                LoggerUtil.warning("[AttributePotion] 读取按键槽位失败: " + error);
                onAbort.run();
            } else if (Bukkit.getPlayer(player.getUniqueId()) != player) {
                onAbort.run();
            } else {
                callback.accept(itemStack);
            }
        })));
    }

    private static void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        AttributePotion plugin = AttributePotion.getInstance();
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private static boolean tryLock(Player player) {
        long now = System.currentTimeMillis();
        boolean[] acquired = new boolean[1];
        BUSY_UNTIL.compute(player.getUniqueId(), (uuid, until) -> {
            if (until != null && until > now) {
                return until;
            }
            acquired[0] = true;
            return now + BUSY_TIMEOUT_MILLIS;
        });
        return acquired[0];
    }

    private static void unlock(Player player) {
        BUSY_UNTIL.remove(player.getUniqueId());
    }

    private static void startPressTask(Player player, String rawKey, SlotAccess slot, PotionConfig config, PressDisplayReporter reporter) {
        if (KeyPressTracker.hasTask(player, rawKey)) {
            return;
        }

        KeyPressTracker.registerTask(player, rawKey, new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getPlayer(player.getUniqueId()) != player) {
                    KeyPressTracker.clear(player, rawKey);
                    cancel();
                    return;
                }

                // 松开事件会先取消这个任务，走到这里说明松开比按下的异步回调先处理完
                if (!KeyPressTracker.isPressing(player, rawKey)) {
                    interrupt(player, rawKey, reporter);
                    cancel();
                    return;
                }

                double progress = KeyPressTracker.progress(player, rawKey, config.getPress());
                reportProgress(player, rawKey, config, reporter, progress);
                if (progress >= 1) {
                    KeyPressTracker.cancelTask(player, rawKey);
                    cancel();
                    fire(player, rawKey, slot, config, reporter);
                }
            }
        }.runTaskTimer(AttributePotion.getInstance(), 1L, PRESS_UPDATE_INTERVAL));
    }

    private static void startDecayTask(Player player, String rawKey, PotionConfig config, PressDisplayReporter reporter) {
        if (KeyPressTracker.hasTask(player, rawKey)) {
            return;
        }

        KeyPressTracker.registerTask(player, rawKey, new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getPlayer(player.getUniqueId()) != player) {
                    KeyPressTracker.clear(player, rawKey);
                    cancel();
                    return;
                }

                if (KeyPressTracker.isPressing(player, rawKey)) {
                    KeyPressTracker.cancelTask(player, rawKey);
                    cancel();
                    return;
                }

                double progress = KeyPressTracker.progress(player, rawKey, config.getPress());
                reportProgress(player, rawKey, config, reporter, progress);
                if (progress <= 0) {
                    interrupt(player, rawKey, reporter);
                    cancel();
                }
            }
        }.runTaskTimer(AttributePotion.getInstance(), PRESS_UPDATE_INTERVAL, PRESS_UPDATE_INTERVAL));
    }

    static void interrupt(Player player, String rawKey, PressDisplayReporter reporter) {
        KeyPressTracker.clear(player, rawKey);
        if (reporter != null) {
            reporter.stop(player);
        }
    }

    static void interruptActive(Player player, PressDisplayReporter reporter) {
        KeyPressTracker.clearActive(player);
        if (reporter != null) {
            reporter.stop(player);
        }
    }

    /**
     * 清理玩家的所有按键状态，在玩家退出时调用。
     *
     * @param uuid 玩家 UUID
     */
    public static void clearPlayer(UUID uuid) {
        KeyPressTracker.clearAll(uuid);
        BUSY_UNTIL.remove(uuid);
    }

    private static void reportProgress(Player player, String rawKey, PotionConfig config, PressDisplayReporter reporter) {
        reportProgress(player, rawKey, config, reporter, KeyPressTracker.progress(player, rawKey, config.getPress()));
    }

    private static void reportProgress(Player player, String rawKey, PotionConfig config, PressDisplayReporter reporter, double progress) {
        if (reporter != null) {
            reporter.report(player, rawKey, config, Math.max(0, Math.min(1, progress)));
        }
    }

    static String normalizeKey(String key) {
        String normalized = key.trim().toUpperCase();
        if (normalized.startsWith("KEY_")) {
            normalized = normalized.substring(4);
        }
        return normalized;
    }
}
