package me.chenfeng.attributepotion.listener;

import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.enums.MatchMode;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import me.chenfeng.attributepotion.utils.ItemConsumeUtil;
import me.chenfeng.attributepotion.utils.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PotionUseListener implements Listener {

    /**
     * 处理点击触发的药水使用。
     * <p>
     * 新版本 Bukkit 会分别触发主手和副手交互事件，这里以主手事件为入口，在方法内部优先检查副手，再检查主手。
     * 例外是右键空气且主手为空：客户端只发副手的数据包，没有主手事件，要从副手事件进入。
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && !(event.getAction() == Action.RIGHT_CLICK_AIR && ItemUtil.isAir(player.getInventory().getItemInMainHand()))) {
            return;
        }

        // 其他插件禁止了这次物品使用时不再处理。右键空气的事件默认就是 cancelled 状态，所以不能看 isCancelled
        TriggerType triggerType = toTriggerType(event.getAction());
        if (triggerType == null || event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        UseResult result = tryUseHands(player, event.getAction(), triggerType);
        if (result.used != null) {
            event.setCancelled(true);
            if (result.used.config.getOptional().isEnabled(OptionKey.CONSUME, true)) {
                consumeItem(player, result.used.hand, result.used.itemStack, result.used.config);
            }
        } else if (result.denied && isRightClick(event.getAction())) {
            // 药水因冷却或条件用不了时，不能让原版继续吃掉或扔出这个物品
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /**
     * 名称匹配模式下，禁止用铁砧把普通物品改名成药水。
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!ConfigManager.isAnvilProtect() || ConfigManager.getMatchMode() != MatchMode.NAME) {
            return;
        }

        PotionConfig resultPotion = PotionHandler.matchPotion(event.getResult());
        if (resultPotion != null && resultPotion != PotionHandler.matchPotion(event.getInventory().getItem(0))) {
            event.setResult(null);
        }
    }

    /**
     * 处理配置为可食用材料的原版消耗事件。
     */
    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        ItemStack itemStack = event.getItem();
        if (ItemUtil.isAir(itemStack) || !ConfigManager.isConsumableType(itemStack.getType())) {
            return;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null) {
            return;
        }
        if (!ItemConsumeUtil.canUseByNbtCount(itemStack)) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        boolean used = PotionHandler.usePotion(player, itemStack, config, null);
        boolean consume = config.getOptional().isEnabled(OptionKey.CONSUME, true);
        if (!used || !consume) {
            event.setCancelled(true);
        } else if (ItemConsumeUtil.hasNbtCount(itemStack)) {
            event.setCancelled(true);
            EquipmentSlot hand = findConsumeHand(player, itemStack);
            ItemConsumeUtil.consumeHand(player, hand, getHandItem(player, hand), config);
        }
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
     * 先尝试副手，再尝试主手。
     */
    private UseResult tryUseHands(Player player, Action action, TriggerType triggerType) {
        UseResult offHand = tryUseHand(player, action, triggerType, EquipmentSlot.OFF_HAND,
                player.getInventory().getItemInOffHand());
        if (offHand.used != null) {
            return offHand;
        }

        UseResult mainHand = tryUseHand(player, action, triggerType, EquipmentSlot.HAND,
                player.getInventory().getItemInMainHand());
        return mainHand.used != null ? mainHand : new UseResult(null, offHand.denied || mainHand.denied);
    }

    private UseResult tryUseHand(Player player, Action action, TriggerType triggerType,
                                 EquipmentSlot hand, ItemStack itemStack) {
        if (ItemUtil.isAir(itemStack) || (isRightClick(action) && ConfigManager.isConsumableType(itemStack.getType()))) {
            return UseResult.NONE;
        }

        PotionConfig config = PotionHandler.matchPotion(itemStack);
        if (config == null || !PotionHandler.allowsTrigger(config, triggerType)) {
            return UseResult.NONE;
        }

        boolean used = ItemConsumeUtil.canUseByNbtCount(itemStack)
                && PotionHandler.usePotion(player, itemStack, config, triggerType);
        return used ? new UseResult(new UsedPotion(hand, itemStack, config), false) : new UseResult(null, true);
    }

    /**
     * 药水成功使用后，从对应装备槽位消耗一个物品。
     * <p>
     * 使用过程中 success 动作可能已经动过玩家手上的物品（例如清空背包），
     * 这时手上已不是原来的物品，不能再写回去，否则会把物品变回来。
     */
    private void consumeItem(Player player, EquipmentSlot hand, ItemStack itemStack, PotionConfig config) {
        ItemStack current = getHandItem(player, hand);
        if (isSameItem(current, itemStack)) {
            ItemConsumeUtil.consumeHand(player, hand, current, config);
        }
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

    private ItemStack getHandItem(Player player, EquipmentSlot hand) {
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

    /**
     * 一次点击的处理结果。used 不为空表示已使用；denied 表示物品是可用的药水，但这次没能使用。
     */
    private static final class UseResult {
        private static final UseResult NONE = new UseResult(null, false);

        private final UsedPotion used;
        private final boolean denied;

        private UseResult(UsedPotion used, boolean denied) {
            this.used = used;
            this.denied = denied;
        }
    }
}
