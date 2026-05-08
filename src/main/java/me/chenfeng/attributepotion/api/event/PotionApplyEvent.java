package me.chenfeng.attributepotion.api.event;

import lombok.Getter;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

public class PotionApplyEvent extends PotionEvent {
    private static final HandlerList handlers = new HandlerList();

    @Getter
    private final ActivePotion activePotion;

    public PotionApplyEvent(Player who, PotionConfig potionConfig, ActivePotion activePotion) {
        super(who, potionConfig);
        this.activePotion = activePotion;
    }

    public static void callEvent(Player who, PotionConfig potionConfig, ActivePotion activePotion) {
        Bukkit.getPluginManager().callEvent(new PotionApplyEvent(who, potionConfig, activePotion));
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

}
