package me.chenfeng.attributepotion.listener.hook;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import me.chenfeng.attributepotion.utils.ItemConsumeUtil;
import me.chenfeng.attributepotion.utils.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Consumer;

final class KeySlotPotionHandler {
    private static final long PRESS_UPDATE_INTERVAL = 1L;

    private KeySlotPotionHandler() {}

    interface PressDisplayReporter {
        void report(Player player, String key, PotionConfig config, double progress);

        void stop(Player player);
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

    static boolean useSlotPotion(Player player, ItemStack itemStack, Consumer<ItemStack> itemUpdater) {
        if (ItemUtil.isAir(itemStack)) {
            return false;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null) {
            return false;
        }
        if (config.getPress() > 0) {
            return false;
        }
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            return false;
        }

        boolean used = PotionHandler.usePotion(player, itemStack, TriggerType.KEY);
        if (!used) {
            return false;
        }

        if (config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
            itemUpdater.accept(ItemConsumeUtil.consumeOne(itemStack, config));
        }
        return true;
    }

    static boolean handleKeyPress(Player player,
                                  String rawKey,
                                  ItemStack itemStack,
                                  Consumer<ItemStack> itemUpdater,
                                  PressDisplayReporter displayReporter) {
        boolean newPress = KeyPressTracker.record(player, rawKey);
        if (!newPress) {
            return false;
        }

        if (ItemUtil.isAir(itemStack)) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        if (config.getPress() <= 0) {
            boolean used = useSlotPotion(player, itemStack, itemUpdater);
            KeyPressTracker.clear(player, rawKey);
            return used;
        }

        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        if (!PotionHandler.canUsePotion(player, config, TriggerType.KEY)) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        KeyPressTracker.cancelTask(player, rawKey);
        reportProgress(player, rawKey, config, displayReporter);
        startPressTask(player, rawKey, itemStack, itemUpdater, config, displayReporter);
        return false;
    }

    static boolean useSlotPotionOnRelease(Player player, String rawKey, ItemStack itemStack, Consumer<ItemStack> itemUpdater) {
        return useSlotPotionOnRelease(player, rawKey, itemStack, itemUpdater, null);
    }

    static boolean useSlotPotionOnRelease(Player player,
                                          String rawKey,
                                          ItemStack itemStack,
                                          Consumer<ItemStack> itemUpdater,
                                          PressDisplayReporter displayReporter) {
        if (ItemUtil.isAir(itemStack)) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null || config.getPress() <= 0) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        KeyPressTracker.cancelTask(player, rawKey);
        double pressedSeconds = KeyPressTracker.releaseSeconds(player, rawKey);
        if (pressedSeconds < config.getPress()) {
            reportProgress(player, rawKey, config, displayReporter);
            startDecayTask(player, rawKey, config, displayReporter);
            return false;
        }
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            interrupt(player, rawKey, displayReporter);
            return false;
        }

        boolean used = PotionHandler.usePotion(player, itemStack, TriggerType.KEY);
        interrupt(player, rawKey, displayReporter);
        if (used && config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
            itemUpdater.accept(ItemConsumeUtil.consumeOne(itemStack, config));
        }
        return used;
    }

    private static void startPressTask(Player player,
                                       String rawKey,
                                       ItemStack itemStack,
                                       Consumer<ItemStack> itemUpdater,
                                       PotionConfig config,
                                       PressDisplayReporter displayReporter) {
        if (KeyPressTracker.hasTask(player, rawKey)) {
            return;
        }

        KeyPressTracker.registerTask(player, rawKey, new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    interrupt(player, rawKey, displayReporter);
                    cancel();
                    return;
                }

                if (!KeyPressTracker.isPressing(player, rawKey)) {
                    KeyPressTracker.cancelTask(player, rawKey);
                    cancel();
                    return;
                }

                double progress = KeyPressTracker.progress(player, rawKey, config.getPress());
                reportProgress(player, rawKey, config, displayReporter, progress);

                if (progress < 1) {
                    return;
                }

                boolean used = usePressedPotion(player, rawKey, itemStack, itemUpdater, config);
                interrupt(player, rawKey, displayReporter);
                cancel();
            }
        }.runTaskTimer(AttributePotion.getInstance(), 1L, PRESS_UPDATE_INTERVAL));
    }

    private static void startDecayTask(Player player,
                                       String rawKey,
                                       PotionConfig config,
                                       PressDisplayReporter displayReporter) {
        if (KeyPressTracker.hasTask(player, rawKey)) {
            return;
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    interrupt(player, rawKey, displayReporter);
                    cancel();
                    return;
                }

                if (KeyPressTracker.isPressing(player, rawKey)) {
                    KeyPressTracker.cancelTask(player, rawKey);
                    cancel();
                    return;
                }

                double progress = KeyPressTracker.progress(player, rawKey, config.getPress());
                reportProgress(player, rawKey, config, displayReporter, progress);
                if (progress <= 0) {
                    interrupt(player, rawKey, displayReporter);
                    cancel();
                }
            }
        }.runTaskTimer(AttributePotion.getInstance(), PRESS_UPDATE_INTERVAL, PRESS_UPDATE_INTERVAL);

        KeyPressTracker.registerTask(player, rawKey, task);
    }

    private static boolean usePressedPotion(Player player,
                                           String rawKey,
                                           ItemStack itemStack,
                                           Consumer<ItemStack> itemUpdater,
                                           PotionConfig config) {
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            return false;
        }

        boolean used = PotionHandler.usePotion(player, itemStack, config, TriggerType.KEY);
        if (used && config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
            itemUpdater.accept(ItemConsumeUtil.consumeOne(itemStack, config));
        }
        return used;
    }

    static void interrupt(Player player, String rawKey, PressDisplayReporter displayReporter) {
        KeyPressTracker.clear(player, rawKey);
        if (displayReporter != null) {
            displayReporter.stop(player);
        }
    }

    static void interruptActive(Player player, PressDisplayReporter displayReporter) {
        KeyPressTracker.clearActive(player);
        if (displayReporter != null) {
            displayReporter.stop(player);
        }
    }

    private static void reportProgress(Player player, String rawKey, PotionConfig config, PressDisplayReporter displayReporter) {
        reportProgress(player, rawKey, config, displayReporter, KeyPressTracker.progress(player, rawKey, config.getPress()));
    }

    private static void reportProgress(Player player,
                                       String rawKey,
                                       PotionConfig config,
                                       PressDisplayReporter displayReporter,
                                       double progress) {
        if (displayReporter != null) {
            displayReporter.report(player, rawKey, config, Math.max(0, Math.min(1, progress)));
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
