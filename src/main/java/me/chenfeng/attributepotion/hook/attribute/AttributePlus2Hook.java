package me.chenfeng.attributepotion.hook.attribute;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.serverct.ersha.jd.AttributeAPI;

import java.util.List;

public class AttributePlus2Hook extends AbstractAttributeHook {

    @Override
    public void addAttribute(Entity entity, String source, List<String> attributes) {
        if(!(entity instanceof Player)) {
            return;
        }
        Player player = (Player)entity;
        AttributeAPI.addAttribute(player, source, attributes);
        AttributeAPI.updateEntityAttribute(entity);
    }

    @Override
    public void takeAttribute(Entity entity, String source) {
        if(!(entity instanceof Player)) {
            return;
        }
        Player player = (Player)entity;
        AttributeAPI.deleteAttribute(player, source);
        AttributeAPI.updateEntityAttribute(entity);
    }
}
