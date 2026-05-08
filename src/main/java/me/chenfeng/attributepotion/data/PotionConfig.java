package me.chenfeng.attributepotion.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.chenfeng.attributepotion.manager.constructor.CommandConfig;
import me.chenfeng.attributepotion.manager.constructor.OptionalConfig;
import me.chenfeng.attributepotion.manager.constructor.RegenConfig;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter @RequiredArgsConstructor
public class PotionConfig {

    private final String key;
    private final String match;
    private final String group;
    private final double cooldown;
    private final double press;
    private final Set<TriggerType> triggers;
    private final List<String> conditions;
    private final double time;
    private final Map<String, String> attributes;
    private final List<PotionEffect> effects;
    private final RegenConfig regen;
    private final CommandConfig commands;
    private final double distance;
    private final OptionalConfig optional;
}
