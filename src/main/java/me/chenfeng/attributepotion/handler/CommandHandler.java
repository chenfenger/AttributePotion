package me.chenfeng.attributepotion.handler;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.hook.PAPIHook;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CommandHandler implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            AttributePotion.getInstance().reloadPluginConfig();
            sender.sendMessage(ConfigManager.getMessage("reload", "[AttributePotion] Reloaded."));
            return true;
        }

        if ((args.length == 3 || args.length == 4) && args[0].equalsIgnoreCase("addPotion")) {
            Player player = Bukkit.getPlayer(args[1]);
            if (player == null) {
                sender.sendMessage("§b[属性药水]§c该玩家不在线！");
                return true;
            }

            String potionKey = args[2];
            if (ConfigManager.getPotionConfig(potionKey) == null) {
                sender.sendMessage("§b[属性药水]§c不存在 " + potionKey + " 药水，请输入正确的药水节点");
                return true;
            }

            boolean used = args.length == 4 && args[3].equalsIgnoreCase("force")
                    ? PotionHandler.forceUsePotion(player, potionKey)
                    : PotionHandler.usePotion(player, potionKey);
            sender.sendMessage(used
                    ? "§b[属性药水]§a已给玩家 " + player.getName() + " 添加药水 " + potionKey
                    : "§b[属性药水]§c添加失败，请检查玩家条件、冷却或药水配置");
            return true;
        }

        if (args.length == 4 && (args[0].equalsIgnoreCase("addTime") || args[0].equalsIgnoreCase("addAttr"))) {
            Player player = Bukkit.getPlayer(args[1]);
            if (player == null) {
                sender.sendMessage("§b[属性药水]§c该玩家不在线！");
                return true;
            }

            String potionKey = args[2];
            if (!PlayerManager.getOrCreateProfile(player).getActivePotions().containsKey(potionKey)) {
                sender.sendMessage("§b[属性药水]§c玩家没有 " + potionKey + " 药水效果，请输入已有的药水节点");
                return true;
            }

            double time;
            try {
                time = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§b[属性药水]§c延长秒数必须是数字");
                return true;
            }

            boolean added = PotionHandler.addPotionTime(player, potionKey, time);
            if (added) {
                Map<String, String> replacements = new java.util.HashMap<>();
                replacements.put("%player%", player.getName());
                replacements.put("%potion%", potionKey);
                replacements.put("%time%", String.valueOf(time));
                sender.sendMessage(ConfigManager.getMessage("addTime", "§b[属性药水]§a已给 %player% 的 %potion% 延长 %time% 秒")
                        .replace("%player%", player.getName())
                        .replace("%potion%", potionKey)
                        .replace("%time%", String.valueOf(time)));
            } else {
                sender.sendMessage("§b[属性药水]§c延长失败，该药水可能是永久药水或配置不存在");
            }
            return true;
        }

        sendHelp(sender);
        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("reload");
            list.add("addPotion");
            list.add("addTime");
            return filter(list, args[0]);
        }

        if (args.length == 2 && isPlayerArgCommand(args[0])) {
            String latest = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(latest)) {
                    list.add(player.getName());
                }
            }
            return list;
        }

        if (args.length == 3 && isPlayerArgCommand(args[0])) {
            list.addAll(ConfigManager.getAllPotionData().keySet());
            return filter(list, args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("addPotion")) {
            list.add("force");
            return filter(list, args[3]);
        }
        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§b[属性药水]apn reload - 重载插件配置");
        sender.sendMessage("§b[属性药水]apn addTime 玩家名 药水节点 延长秒数 - 给玩家延长药水时间");
        sender.sendMessage("§b[属性药水]apn addPotion 玩家名 药水节点 [force] - 给玩家添加药水");
        sender.sendMessage("§f    - force 会跳过条件和冷却，强制使用");
    }

    private boolean isPlayerArgCommand(String command) {
        return command.equalsIgnoreCase("addPotion")
                || command.equalsIgnoreCase("addTime")
                || command.equalsIgnoreCase("addAttr");
    }

    private List<String> filter(List<String> list, String latest) {
        if (latest == null || latest.isEmpty()) {
            return list;
        }
        String lower = latest.toLowerCase(Locale.ROOT);
        list.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(lower));
        return list;
    }

    /**
     * 执行命令列表。
     * <p>
     * 遍历命令列表，根据命令前缀解析并执行不同类型的命令：
     * - [msg]: 发送聊天消息
     * - [console]: 以控制台身份执行命令
     * - [op]: 临时提升 OP 执行命令
     * - [title]: 显示标题（大标题<->小标题<->淡入<->持续<->淡出）
     * - [actionbar]: 显示 ActionBar
     * - [bossbar]: 显示 BossBar（标题<->颜色<->样式<->持续时间）
     *
     * @param player 执行命令的玩家
     * @param commands 要执行的命令列表
     * @param potionDuration 药水持续时间（秒），用于定时命令
     */
    public static void executeCommands(Player player, List<String> commands, double potionDuration) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        for (String command : commands) {
            if (command == null || command.isEmpty()) {
                continue;
            }

            executeSingleCommand(player, command, potionDuration);
        }
    }

    /**
     * 执行单个命令，根据前缀解析命令类型。
     *
     * @param player 玩家对象
     * @param command 原始命令字符串
     * @param potionDuration 药水持续时间
     */
    private static void executeSingleCommand(Player player, String command, double potionDuration) {
        String replacedCommand = PAPIHook.replacePlaceholders(player, command);

        if (replacedCommand.startsWith("[msg]")) {
            executeMsgCommand(player, replacedCommand.substring(5));
        } else if (replacedCommand.startsWith("[console]")) {
            executeConsoleCommand(replacedCommand.substring(9));
        } else if (replacedCommand.startsWith("[op]")) {
            executeOpCommand(player, replacedCommand.substring(4));
        } else if (replacedCommand.startsWith("[title]")) {
            executeTitleCommand(player, replacedCommand.substring(7));
        } else if (replacedCommand.startsWith("[actionbar]")) {
            executeActionbarCommand(player, replacedCommand.substring(11));
        } else if (replacedCommand.startsWith("[bossbar]")) {
            executeBossbarCommand(player, replacedCommand.substring(9), potionDuration);
        } else {
            Bukkit.dispatchCommand(player, replacedCommand);
        }
    }

    /**
     * 执行消息命令。
     *
     * @param player 玩家对象
     * @param message 消息内容
     */
    private static void executeMsgCommand(Player player, String message) {
        if (message != null && !message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    /**
     * 执行控制台命令。
     *
     * @param command 命令内容
     */
    private static void executeConsoleCommand(String command) {
        if (command != null && !command.isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    /**
     * 执行 OP 命令（临时提升权限）。
     *
     * @param player 玩家对象
     * @param command 命令内容
     */
    private static void executeOpCommand(Player player, String command) {
        if (command == null || command.isEmpty()) {
            return;
        }

        boolean wasOp = player.isOp();
        try {
            player.setOp(true);
            Bukkit.dispatchCommand(player, command);
        } finally {
            player.setOp(wasOp);
        }
    }

    /**
     * 执行标题命令。
     * <p>
     * 格式：大标题<->小标题<->淡入时间<->持续时间<->淡出时间
     *
     * @param player 玩家对象
     * @param params 参数字符串
     */
    private static void executeTitleCommand(Player player, String params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        String[] parts = params.split(java.util.regex.Pattern.quote(ConfigManager.getSplit()), 5);
        if (parts.length < 5) {
            return;
        }

        try {
            String mainTitle = parts[0];
            String subTitle = parts[1];
            int fadeIn = Integer.parseInt(parts[2]);
            int stay = Integer.parseInt(parts[3]);
            int fadeOut = Integer.parseInt(parts[4]);

            player.sendTitle(mainTitle, subTitle, fadeIn, stay, fadeOut);
        } catch (NumberFormatException e) {
            LoggerUtil.warning("[AttributePotion] Title 命令参数格式错误: " + params);
        }
    }

    /**
     * 执行 ActionBar 命令。
     *
     * @param player 玩家对象
     * @param message ActionBar 消息内容
     */
    private static void executeActionbarCommand(Player player, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        player.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message)
        );
    }

    /**
     * 执行 BossBar 命令。
     * <p>
     * 格式：标题<->颜色<->样式<->持续时间
     *
     * @param player 玩家对象
     * @param params 参数字符串
     * @param potionDuration 药水持续时间（秒）
     */
    private static void executeBossbarCommand(Player player, String params, double potionDuration) {
        if (params == null || params.isEmpty()) {
            return;
        }

        String[] parts = params.split(java.util.regex.Pattern.quote(ConfigManager.getSplit()), 4);
        if (parts.length < 4) {
            return;
        }

        try {
            String title = parts[0];
            BarColor color = parseBarColor(parts[1]);
            BarStyle style = parseBarStyle(parts[2]);
            double duration = Double.parseDouble(parts[3]);

            BossBar bossBar = Bukkit.createBossBar(title, color, style);
            bossBar.addPlayer(player);

            bossBar.setVisible(true);
            if (duration > 0) {

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        bossBar.removeAll();
                    }
                }.runTaskLater(AttributePotion.getInstance(), (long) (duration * 20));
            }
        } catch (NumberFormatException e) {
            LoggerUtil.warning("[AttributePotion] BossBar 命令参数格式错误: " + params);
        }
    }

    /**
     * 解析 BossBar 颜色。
     *
     * @param colorName 颜色名称
     * @return BarColor 枚举值，默认为 BLUE
     */
    private static BarColor parseBarColor(String colorName) {
        try {
            return BarColor.valueOf(colorName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BarColor.BLUE;
        }
    }

    /**
     * 解析 BossBar 样式。
     *
     * @param styleName 样式名称
     * @return BarStyle 枚举值，默认为 SOLID
     */
    private static BarStyle parseBarStyle(String styleName) {
        try {
            return BarStyle.valueOf(styleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BarStyle.SOLID;
        }
    }
}
