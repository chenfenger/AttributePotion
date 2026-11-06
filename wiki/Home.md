# AttributePotion Wiki

AttributePotion 是面向 Bukkit/Spigot/Paper 1.12.2 到 1.21.x 的属性药水插件。它按物品名、Lore 或 NBT 识别药水，使用后给玩家加属性、原版效果、恢复效果，并执行配置好的动作。

## 目录

| 页面 | 内容 |
| --- | --- |
| [安装与构建](安装与构建.md) | 依赖、安装、指令、权限、从源码构建 |
| [全局配置](全局配置.md) | `config.yml` 每个选项的作用 |
| [药水配置](药水配置.md) | `potion/*.yml` 每个字段的作用和取值 |
| [触发与按键](触发与按键.md) | 左右键、原版消耗、按键槽位、长按、HUD |
| [条件与动作](条件与动作.md) | 条件表达式语法、动作前缀 |
| [数据存储](数据存储.md) | SQLite/MySQL、存档时机、旧数据兼容 |
| [占位符](占位符.md) | `%apn_xxx%` 占位符列表 |
| [开发 API](开发API.md) | `PotionAPI` 和事件 |
| [常见问题](常见问题.md) | 排查方法和容易误解的行为 |
| [更新日志](更新日志.md) | 版本变更 |
| [实测清单](实测清单.md) | 上线前的验证项目 |

## 五分钟上手

1. 把 jar 放进 `plugins`，启动一次生成配置。
2. 在 `config.yml` 确认 `attribute-plugin` 和你的属性插件一致。
3. 在 `plugins/AttributePotion/potion/` 新建一个 yml：

   ```yaml
   力量药水:
     match: '&c力量药水'
     cooldown: 30
     time: 60
     triggers:
       - RIGHT_CLICK_AIR
       - RIGHT_CLICK_BLOCK
     attributes:
       物理伤害: "100"
   ```

4. 执行 `/apn reload`。
5. 拿一个显示名为红色“力量药水”的物品右键使用。
