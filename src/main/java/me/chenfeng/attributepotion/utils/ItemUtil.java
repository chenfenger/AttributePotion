package me.chenfeng.attributepotion.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class ItemUtil {

    public static boolean isAir(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR;
    }
}
