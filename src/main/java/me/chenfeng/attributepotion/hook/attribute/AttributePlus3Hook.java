package me.chenfeng.attributepotion.hook.attribute;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.serverct.ersha.api.AttributeAPI;
import org.serverct.ersha.attribute.data.AttributeData;

import java.util.List;

public class AttributePlus3Hook extends AbstractAttributeHook {

    @Override
    public void addAttribute(Entity entity, String source, List<String> attributes) {
        if(!(entity instanceof LivingEntity)) {
            return;
        }
        LivingEntity livingEntity = (LivingEntity) entity;
        AttributeData data = AttributeAPI.getAttrData(livingEntity);
        AttributeAPI.addSourceAttribute(data, source, attributes);
        AttributeAPI.updateAttribute(livingEntity);
    }

    @Override
    public void takeAttribute(Entity entity, String source) {
        if(!(entity instanceof LivingEntity)) {
            return;
        }
        LivingEntity livingEntity = (LivingEntity) entity;
        AttributeData data = AttributeAPI.getAttrData(livingEntity);
        AttributeAPI.takeSourceAttribute(data, source);
        AttributeAPI.updateAttribute(livingEntity);
    }

}
