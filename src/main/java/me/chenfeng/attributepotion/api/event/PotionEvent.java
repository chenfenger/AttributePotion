package me.chenfeng.attributepotion.api.event;


import lombok.Getter;
import me.chenfeng.attributepotion.data.PotionConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;

@Getter
public abstract class PotionEvent extends PlayerEvent {

    protected final PotionConfig potionConfig;

    protected PotionEvent(Player who, PotionConfig potionConfig) {
        super(who);
        this.potionConfig = potionConfig;
    }

}
