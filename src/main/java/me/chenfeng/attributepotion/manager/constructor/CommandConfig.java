package me.chenfeng.attributepotion.manager.constructor;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.*;

/**
 * 命令配置类，用于存储药水在不同阶段执行的命令列表。
 * <p>
 * 支持在药水的不同生命周期阶段（成功、结束）执行不同的命令，
 * 还支持周期性执行（tick）的命令配置。
 */
@Getter
@ToString
@EqualsAndHashCode
public class CommandConfig {
    /**
     * 命令阶段到命令列表的映射。
     * <p>
     * 键为命令执行阶段，值为该阶段要执行的命令字符串列表。
     */
    private final EnumMap<CommandPhase, List<String>> commands;
    
    /**
     * Tick周期命令配置。
     * <p>
     * 键为 tick 间隔（单位：游戏刻，20 tick = 1 秒），值为该间隔下要执行的命令列表。
     * 使用 TreeMap 保持顺序，便于按间隔大小排序。
     * -- GETTER --
     *  获取所有 tick 周期命令配置。
     *
     */
    private final Map<Integer, List<String>> tickCommands;

    /**
     * 构造命令配置对象（不包含 tick 配置）。
     * 
     * @param commands 命令阶段到命令列表的映射
     */
    public CommandConfig(Map<CommandPhase, List<String>> commands) {
        this(commands, Collections.emptyMap());
    }
    
    /**
     * 构造命令配置对象（包含 tick 配置）。
     * 
     * @param commands 命令阶段到命令列表的映射
     * @param tickCommands tick 周期命令配置，键为间隔刻数，值为命令列表
     */
    public CommandConfig(Map<CommandPhase, List<String>> commands, Map<Integer, List<String>> tickCommands) {
        EnumMap<CommandPhase, List<String>> map = new EnumMap<>(CommandPhase.class);
        if (commands != null) {
            for (Map.Entry<CommandPhase, List<String>> e : commands.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.put(e.getKey(), e.getValue());
                }
            }
        }
        this.commands = map;
        
        // 处理 tick 命令配置
        if (tickCommands != null && !tickCommands.isEmpty()) {
            this.tickCommands = Collections.unmodifiableMap(new TreeMap<>(tickCommands));
        } else {
            this.tickCommands = Collections.emptyMap();
        }
    }

    /**
     * 获取指定阶段的命令列表。
     * 
     * @param phase 命令执行阶段
     * @return 该阶段的命令列表，如果不存在则返回空列表
     */
    public List<String> get(CommandPhase phase) {
        List<String> list = commands.get(phase);
        return list != null ? list : Collections.emptyList();
    }
    
    /**
     * 获取指定 tick 间隔的命令列表。
     * 
     * @param tickInterval tick 间隔（游戏刻）
     * @return 该间隔下的命令列表，如果不存在则返回空列表
     */
    public List<String> getTickCommands(int tickInterval) {
        List<String> list = tickCommands.get(tickInterval);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 获取所有命令配置的不可变映射视图。
     * 
     * @return 不可变的命令阶段到命令列表的映射
     */
    public Map<CommandPhase, List<String>> asMap() {
        return Collections.unmodifiableMap(commands);
    }
}

