package me.chenfeng.attributepotion.utils;

import me.chenfeng.attributepotion.manager.ConfigManager;
import org.bukkit.Bukkit;

public class LoggerUtil {

    private static final String PREFIX = "";

    public static void info(String message) {
        Bukkit.getLogger().info(PREFIX + message);
    }

    public static void warning(String message) {
        Bukkit.getLogger().warning(PREFIX + message);
    }

    public static void severe(String message) {
        Bukkit.getLogger().severe(PREFIX + message);
    }

    public static void debug(String message) {
        if (ConfigManager.isDebug()) {
            Bukkit.getLogger().info(PREFIX + message);
        }
    }
}
