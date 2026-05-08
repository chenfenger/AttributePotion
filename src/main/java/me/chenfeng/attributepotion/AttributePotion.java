package me.chenfeng.attributepotion;

import lombok.Getter;
import me.chenfeng.attributepotion.handler.CommandHandler;
import me.chenfeng.attributepotion.hook.PAPIHook;
import me.chenfeng.attributepotion.hook.attribute.*;
import me.chenfeng.attributepotion.hook.mana.ManaHook;
import me.chenfeng.attributepotion.hook.mana.SkillApiManaHook;
import me.chenfeng.attributepotion.hook.mana.YetzirahManaHook;
import me.chenfeng.attributepotion.listener.DataListener;
import me.chenfeng.attributepotion.listener.PotionUseListener;
import me.chenfeng.attributepotion.listener.hook.CloudPickListener;
import me.chenfeng.attributepotion.listener.hook.DragonCoreListener;
import me.chenfeng.attributepotion.listener.hook.GermPluginListener;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.SqlManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

public final class AttributePotion extends JavaPlugin {
    @Getter
    private static AttributePotion instance;

    @Getter
    private AbstractAttributeHook attributeHook;
    @Getter
    private ManaHook manaHook;
    @Getter
    private SqlManager sqlManager;

    public AttributePotion() {
        instance = this;
    }

    @Override
    public void onLoad() {
        if(!getDataFolder().exists()) getDataFolder().mkdirs();
        saveDefaultConfig();
        File file = new File(getDataFolder(), "potion");
        if (!file.exists()) {
            file.mkdirs();
            saveResource("potion/example.yml", false);
        }
        saveResourceIfAbsent("press-hud.yml");
    }

    @Override
    public void onEnable() {
        ConfigManager.loadConfig();
        initDatabase();
        initAttributeHook();
        initManaHook();
        initPapiHook();
        registerListeners();
        registerCommands();
        
        PlayerManager.startExpirationCheckTask(20);
    }

    @Override
    public void onDisable() {
        PlayerManager.stopExpirationCheckTask();
        saveOnlineProfiles();
        PlayerManager.clearAllProfiles();
        if (sqlManager != null) {
            sqlManager.close();
        }
    }

    public void reloadPluginConfig() {
        ConfigManager.loadConfig();
        initAttributeHook();
        initManaHook();
    }

    private void initDatabase() {
        this.sqlManager = new SqlManager(this);
        this.sqlManager.init();
    }

    /**
     * 在插件目录中不存在指定资源时释放默认资源。
     *
     * @param resourcePath jar 内资源路径
     */
    private void saveResourceIfAbsent(String resourcePath) {
        File file = new File(getDataFolder(), resourcePath);
        if (!file.exists()) {
            saveResource(resourcePath, false);
        }
    }

    private void saveOnlineProfiles() {
        if (sqlManager == null) {
            return;
        }
        PlayerManager.getAllProfiles().forEach((uuid, profile) -> sqlManager.saveProfileBlocking(uuid, profile));
    }

    /**
     * 初始化属性系统Hook适配器。
     * <p>
     * 根据配置文件中指定的属性插件类型和实际安装的插件版本，
     * 创建对应的属性系统适配器实例。支持以下属性插件：
     * <ul>
     *   <li>AttributePlus 2.x (AP2)</li>
     *   <li>AttributePlus 3.x (AP3)</li>
     *   <li>SX-Attribute 2.x (SX2)</li>
     *   <li>SX-Attribute 3.x (SX3)</li>
     * </ul>
     * 如果配置的插件未安装或版本不匹配，则attributeHook保持为null。
     */
    private void initPapiHook() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && PAPIHook.getInstance() == null) {
            new PAPIHook();
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new DataListener(), this);
        Bukkit.getPluginManager().registerEvents(new PotionUseListener(), this);
        if (Bukkit.getPluginManager().getPlugin("DragonCore") != null) {
            Bukkit.getPluginManager().registerEvents(new DragonCoreListener(this), this);
        }
        if (Bukkit.getPluginManager().getPlugin("CloudPick") != null) {
            Bukkit.getPluginManager().registerEvents(new CloudPickListener(this), this);
        }
        if (Bukkit.getPluginManager().getPlugin("GermPlugin") != null) {
            Bukkit.getPluginManager().registerEvents(new GermPluginListener(), this);
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("AttributePotion");
        if (command != null) {
            CommandHandler handler = new CommandHandler();
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    private void initAttributeHook() {
        String type = ConfigManager.getAttributePlugin();
        if (type == null) return;

        type = normalizeAttributeType(type);

        AbstractAttributeHook hook = null;
        
        // 检测已安装的属性插件并创建对应版本的Hook适配器
        Plugin attributePlus = Bukkit.getPluginManager().getPlugin("AttributePlus");
        Plugin SXAttribute = Bukkit.getPluginManager().getPlugin("SX-Attribute");

        if (type.startsWith("AP") && attributePlus != null && attributePlus.isEnabled()) {
            String version = attributePlus.getDescription().getVersion();
            if(type.equals("AP2") && version.startsWith("2")) {
                hook = new AttributePlus2Hook();
            }
            else if(type.equals("AP3") && version.startsWith("3")) {
                hook = new AttributePlus3Hook();
            }
        }

        else if (type.startsWith("SX") && SXAttribute != null && SXAttribute.isEnabled()) {
            String version = SXAttribute.getDescription().getVersion();
            if(type.equals("SX2") && version.startsWith("2")) {
                hook = new SXAttribute2Hook();
            }
            else if(type.equals("SX3") && version.startsWith("3")) {
                hook = new SXAttribute3Hook();
            }
        }

        this.attributeHook = hook;
    }

    /**
     * 初始化魔力系统 Hook。
     * <p>
     * SkillAPI 和 Yetzirah 都不是必需依赖，所以这里只在插件实际存在时尝试反射接入。
     * 优先 SkillAPI，未接入时再尝试 Yetzirah，避免两个插件同时存在时重复写入魔力。
     */
    private void initManaHook() {
        this.manaHook = null;

        if (isPluginEnabled("SkillAPI")) {
            ManaHook hook = new SkillApiManaHook();
            if (hook.isAvailable()) {
                this.manaHook = hook;
                LoggerUtil.info("[AttributePotion] 已接入魔力系统: " + hook.getName());
                return;
            }
        }

        if (isPluginEnabled("Yetzirah")) {
            ManaHook hook = new YetzirahManaHook();
            if (hook.isAvailable()) {
                this.manaHook = hook;
                LoggerUtil.info("[AttributePotion] 已接入魔力系统: " + hook.getName());
                return;
            }
        }

        LoggerUtil.debug("[AttributePotion] 未检测到可用的魔力系统 Hook");
    }

    /**
     * 判断指定插件是否已加载并启用。
     */
    private boolean isPluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    private String normalizeAttributeType(String type) {
        String normalized = type.toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        switch (normalized) {
            case "ATTRIBUTEPLUS2":
                return "AP2";
            case "ATTRIBUTEPLUS3":
                return "AP3";
            case "SXATTRIBUTE2":
                return "SX2";
            case "SXATTRIBUTE3":
                return "SX3";
        }
        return normalized;
    }

}
