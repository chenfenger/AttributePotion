package me.chenfeng.attributepotion.hook.attribute;

import org.bukkit.entity.Entity;

import java.util.List;

public abstract class AbstractAttributeHook {

    public abstract void addAttribute(Entity entity, String source, List<String> attributes);

    public void takeAttribute(Entity entity, String source) {
        // 默认什么都不做
    }

}
