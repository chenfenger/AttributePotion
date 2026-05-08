package me.chenfeng.attributepotion.listener;

import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import me.chenfeng.attributepotion.utils.ItemConsumeUtil;
import me.chenfeng.attributepotion.utils.ItemUtil;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PotionUseListener implements Listener {

    /**
     * 处理点击触发的药水使用。
     * <p>
     * 新版本 Bukkit 会分别触发主手和副手交互事件，这里只把主手事件作为入口，
     * 然后在方法内部优先检查副手，再检查主手。
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        long start = debugStart();
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            debugTiming("PlayerInteractEvent ignored duplicated hand=" + event.getHand(), start);
            return;
        }

        TriggerType triggerType = toTriggerType(event.getAction());
        if (triggerType == null) {
            debugTiming("PlayerInteractEvent ignored action=" + event.getAction(), start);
            return;
        }

        UsedPotion usedPotion = tryUseHands(event.getPlayer(), event.getAction(), triggerType);
        if (usedPotion == null) {
            debugTiming("PlayerInteractEvent no potion action=" + event.getAction(), start);
            return;
        }

        event.setCancelled(true);
        if (usedPotion.config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
            consumeItem(event.getPlayer(), usedPotion.hand, usedPotion.itemStack, usedPotion.config);
        }
        debugTiming("PlayerInteractEvent used potion=" + usedPotion.config.getKey() + ", hand=" + usedPotion.hand, start);
    }

    /**
     * 处理配置为可食用材料的原版消耗事件。
     */
    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        long start = debugStart();
        ItemStack itemStack = event.getItem();
        if (ItemUtil.isAir(itemStack) || !isConfiguredConsumable(itemStack)) {
            debugTiming("PlayerItemConsumeEvent ignored material", start);
            return;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null) {
            debugTiming("PlayerItemConsumeEvent no potion match", start);
            return;
        }
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            event.setCancelled(true);
            debugTiming("PlayerItemConsumeEvent denied by nbt count potion=" + config.getKey(), start);
            return;
        }

        boolean used = PotionHandler.usePotion(event.getPlayer(), config.getKey());
        boolean consume = config.getOptional().isEnabled(OptionKey.CONSUME, true);
        if (!used || !consume) {
            event.setCancelled(true);
        } else if (ItemConsumeUtil.hasNbtCount(itemStack)) {
            event.setCancelled(true);
            ItemConsumeUtil.consumeHand(event.getPlayer(), findConsumeHand(event.getPlayer(), itemStack), getHandItem(event.getPlayer(), itemStack), config);
        }
        debugTiming("PlayerItemConsumeEvent potion=" + config.getKey() + ", used=" + used, start);
    }

    /**
     * 将 Bukkit 点击行为转换为药水触发类型。
     */
    private TriggerType toTriggerType(Action action) {
        switch (action) {
            case LEFT_CLICK_AIR:
                return TriggerType.LEFT_CLICK_AIR;
            case LEFT_CLICK_BLOCK:
                return TriggerType.LEFT_CLICK_BLOCK;
            case RIGHT_CLICK_AIR:
                return TriggerType.RIGHT_CLICK_AIR;
            case RIGHT_CLICK_BLOCK:
                return TriggerType.RIGHT_CLICK_BLOCK;
            default:
                return null;
        }
    }

    /**
     * 判断点击行为是否为右键。
     */
    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    /**
     * 判断物品材料是否应该交给原版消耗事件处理，而不是在交互事件中处理。
     */
    private boolean isConfiguredConsumable(ItemStack itemStack) {
        Material material = itemStack.getType();
        for (String type : ConfigManager.getConsumableTypes()) {
            if (material.name().equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 先尝试副手，再尝试主手，并返回第一个成功使用的药水。
     */
    private UsedPotion tryUseHands(Player player, Action action, TriggerType triggerType) {
        long start = debugStart();
        UsedPotion offHand = tryUseHand(player, action, triggerType, EquipmentSlot.OFF_HAND,
                player.getInventory().getItemInOffHand());
        if (offHand != null) {
            debugTiming("tryUseHands: off-hand success", start);
            return offHand;
        }

        UsedPotion mainHand = tryUseHand(player, action, triggerType, EquipmentSlot.HAND,
                player.getInventory().getItemInMainHand());
        debugTiming("tryUseHands: main-hand result=" + (mainHand == null ? "null" : mainHand.config.getKey()), start);
        return mainHand;
    }

    /**
     * 尝试从指定装备槽位匹配并使用药水。
     */
    private UsedPotion tryUseHand(Player player, Action action, TriggerType triggerType,
                                  EquipmentSlot hand, ItemStack itemStack) {
        long start = debugStart();
        if (ItemUtil.isAir(itemStack)) {
            debugTiming("tryUseHand(" + hand + "): air/null", start);
            return null;
        }

        if (isRightClick(action) && isConfiguredConsumable(itemStack)) {
            debugTiming("tryUseHand(" + hand + "): skipped consumable right click", start);
            return null;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null) {
            debugTiming("tryUseHand(" + hand + "): no match", start);
            return null;
        }
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            debugTiming("tryUseHand(" + hand + "): denied by nbt count", start);
            return null;
        }

        boolean used = PotionHandler.usePotion(player, itemStack, config, triggerType);
        debugTiming("tryUseHand(" + hand + "): potion=" + config.getKey() + ", used=" + used, start);
        return used ? new UsedPotion(hand, itemStack, config) : null;
    }

    /**
     * 药水成功使用后，从对应装备槽位消耗一个物品。
     */
    private void consumeItem(Player player, EquipmentSlot hand, ItemStack itemStack, PotionConfig config) {
        ItemConsumeUtil.consumeHand(player, hand, itemStack, config);
    }

    /**
     * 根据原版消耗事件中的物品，推断玩家实际消耗的是哪只手。
     */
    private EquipmentSlot findConsumeHand(Player player, ItemStack eventItem) {
        if (isSameItem(player.getInventory().getItemInOffHand(), eventItem)) {
            return EquipmentSlot.OFF_HAND;
        }
        return EquipmentSlot.HAND;
    }

    /**
     * 获取原版消耗事件对应手上的实时物品。
     */
    private ItemStack getHandItem(Player player, ItemStack eventItem) {
        EquipmentSlot hand = findConsumeHand(player, eventItem);
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    /**
     * 判断两个物品是否可视为同一个待消耗物品。
     */
    private boolean isSameItem(ItemStack currentItem, ItemStack eventItem) {
        return !ItemUtil.isAir(currentItem) && currentItem.isSimilar(eventItem);
    }

    /**
     * 仅在 debug 开启时返回计时起点。
     */
    private long debugStart() {
        return ConfigManager.isDebug() ? System.nanoTime() : 0L;
    }

    /**
     * 在 debug 开启时输出事件层耗时日志。
     */
    private void debugTiming(String logic, long startNanos) {
        if (startNanos == 0L || !ConfigManager.isDebug()) {
            return;
        }
        LoggerUtil.debug("[AttributePotion][Timing] " + logic + " took "
                + String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0)
                + " ms");
    }

    /**
     * 记录成功使用的药水及其来源槽位。
     */
    private static final class UsedPotion {
        private final EquipmentSlot hand;
        private final ItemStack itemStack;
        private final PotionConfig config;

        private UsedPotion(EquipmentSlot hand, ItemStack itemStack, PotionConfig config) {
            this.hand = hand;
            this.itemStack = itemStack;
            this.config = config;
        }
    }
}
