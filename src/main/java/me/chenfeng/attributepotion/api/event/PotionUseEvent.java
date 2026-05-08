package me.chenfeng.attributepotion.api.event;

import me.chenfeng.attributepotion.data.PotionConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

public class PotionUseEvent extends PotionEvent implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;

    public PotionUseEvent(Player who, PotionConfig potionConfig) {
        super(who, potionConfig);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * 调用药水使用事件并返回是否被取消
     * <p>
     * 该方法会创建一个新的 PotionUseEvent 实例并通过 Bukkit 事件系统发布，
     * 允许其他插件监听并可能取消该事件。
     *
     * @param who 使用药水的玩家
     * @param potionConfig 药水的配置信息
     * @return 如果事件被取消则返回 true，否则返回 false
     */
    public static boolean callEvent(Player who, PotionConfig potionConfig) {
        PotionUseEvent event = new PotionUseEvent(who, potionConfig);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }
}
