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
import me.chenfeng.attributepotion.listener.hook.ArcartXListener;
import me.chenfeng.attributepotion.listener.hook.CloudPickListener;
import me.chenfeng.attributepotion.listener.hook.DragonCoreListener;
import me.chenfeng.attributepotion.listener.hook.GermPluginListener;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.SqlManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
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
    private String attributeHookType;

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
        startAutoSaveTask();
        loadOnlinePlayers();
    }

    @Override
    public void onDisable() {
        PlayerManager.stopExpirationCheckTask();
        if (sqlManager != null) {
            sqlManager.awaitPendingTasks();
            saveOnlineProfiles();
        }
        // 档案已存库，重启后还会恢复，这里只撤销运行时状态，不能执行 end 动作
        PlayerManager.unloadAllProfiles();
        CommandHandler.clearAllActionBossBars();
        PAPIHook.unregisterExpansion();
        if (sqlManager != null) {
            sqlManager.close();
        }
    }

    /**
     * 热重载时玩家已经在线，不会再触发进服事件，要主动加载一次，否则退出时会用空档案覆盖存档。
     */
    private void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerManager.getOrCreateProfile(player);
            sqlManager.loadProfileAsync(player);
        }
        DragonCoreListener.sendYaml();
    }

    /**
     * 定时存档，减少服务器崩溃时丢失的数据。
     */
    private void startAutoSaveTask() {
        long intervalTicks = ConfigManager.getAutoSaveInterval() * 20L;
        if (intervalTicks > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> sqlManager.saveAllAsync(), intervalTicks, intervalTicks);
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

    private void initPapiHook() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && PAPIHook.getInstance() == null) {
            new PAPIHook();
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new DataListener(), this);
        Bukkit.getPluginManager().registerEvents(new PotionUseListener(), this);
        registerKeyHook("DragonCore", () -> Bukkit.getPluginManager().registerEvents(new DragonCoreListener(this), this));
        registerKeyHook("CloudPick", () -> Bukkit.getPluginManager().registerEvents(new CloudPickListener(this), this));
        registerKeyHook("GermPlugin", () -> Bukkit.getPluginManager().registerEvents(new GermPluginListener(), this));
        registerKeyHook("ArcartX", ArcartXListener::new);
    }

    /**
     * 接入按键插件。某个插件版本不兼容时只跳过它，不影响其他接入和插件本身。
     */
    private void registerKeyHook(String pluginName, Runnable register) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            return;
        }
        try {
            register.run();
        } catch (RuntimeException | LinkageError e) {
            LoggerUtil.warning("[AttributePotion] " + pluginName + " 按键接入失败，已跳过: " + e);
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

    /**
     * 根据配置的属性插件类型和实际安装的插件版本创建 Hook。
     * <p>
     * 重载时类型没变就沿用旧实例：SX 的 Hook 在内存里保存各药水的属性，换新实例会让在线玩家的属性对不上。
     */
    private void initAttributeHook() {
        String type = normalizeAttributeType(ConfigManager.getAttributePlugin());
        if (attributeHook != null && type.equals(attributeHookType)) {
            return;
        }

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
        this.attributeHookType = type;
        if (hook == null) {
            LoggerUtil.warning("[AttributePotion] 没有可用的属性插件接入（attribute-plugin: " + type
                    + "），药水的 attributes 不会生效。请确认插件已安装且大版本与配置一致");
        }
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
