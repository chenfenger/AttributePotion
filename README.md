# AttributePotion

AttributePotion 是一个面向 Bukkit/Spigot/Paper 的属性药水插件。插件可以通过物品名称、Lore 或 NBT 识别药水，使用后为玩家添加属性、原版药水效果、恢复效果、命令动作、冷却、BossBar 显示等。

当前版本面向 Java 8 构建，主要兼容 1.12.2 服务端环境。

## 功能

- 支持 AttributePlus 2/3、SX-Attribute 2/3 属性系统。
- 支持普通交互触发、物品消耗触发、DragonCore/GermPlugin/CloudPick 按键触发。
- 支持按键长按 `press`，DragonCore 可显示按压进度 HUD。
- 支持主手/副手使用，优先判断副手，再判断主手。
- 支持 SQLite 和 MySQL 存储，兼容旧版数据读取。
- 支持药水持续时间、冷却、组冷却、覆盖限制、潜行限制、范围效果。
- 支持生命、饥饿、SkillAPI/Yetzirah 魔力恢复。
- 支持按药水配置决定是否显示剩余时间 BossBar。
- 支持 NBT 次数消耗，次数耗尽时可选择移除物品。
- 提供 `PotionAPI` 给其他插件调用。

## 依赖

必需：

- Bukkit/Spigot/Paper
- Java 8 运行环境

可选：

- AttributePlus 或 SX-Attribute：属性加成
- PlaceholderAPI：变量解析
- NBTAPI：NBT 匹配和 NBT 次数消耗
- DragonCore：按键触发和 press HUD
- GermPlugin：按键触发
- CloudPick：按键触发
- SkillAPI：魔力恢复
- Yetzirah：魔力恢复

## 安装

1. 将插件 jar 放入服务器 `plugins` 目录。
2. 启动服务器生成配置。
3. 根据需要修改：
   - `plugins/AttributePotion/config.yml`
   - `plugins/AttributePotion/potion/*.yml`
   - `plugins/AttributePotion/press-hud.yml`
4. 执行 `/apn reload` 或重启服务器。

## 命令

主命令：`/AttributePotion`  
别名：`/apn`

权限：`admin`

| 命令 | 说明 |
| --- | --- |
| `/apn reload` | 重载配置、药水和 Hook |
| `/apn addPotion <玩家> <药水节点>` | 按正常流程给玩家使用药水，会检查条件和冷却 |
| `/apn addPotion <玩家> <药水节点> force` | 强制给玩家使用药水，跳过条件和冷却 |
| `/apn addTime <玩家> <药水节点> <秒数>` | 增加或减少已激活药水的剩余时间 |

## 配置概览

`config.yml` 主要配置：

```yaml
debug: false
load-delay: 60

mysql:
  enable: false
  host: localhost
  port: 3306
  username: root
  password: root
  file_name: AttributePotion
  table_name: data
  driver: 'com.mysql.jdbc.Driver'
  jdbc: '?useSSL=false&useUnicode=true&characterEncoding=utf8'

attribute-plugin: 'AP3'
match-mode: 'name'
contain: true
nbt-key: 'apn'
nbt-count: 'apn_count'
split: '<->'

key-slots:
  Z: '额外槽位1'
  X: '额外槽位2'
  C: '额外槽位3'
```

说明：

- `mysql.enable: false` 时使用 SQLite。
- SQLite 默认文件为 `AttributePotion.db`。如果未配置文件名且存在旧版 `database.db`，会优先读取旧文件。
- `attribute-plugin` 可填：`AP2`、`AP3`、`SX2`、`SX3`。
- `match-mode` 可填：`name`、`lore`、`nbt`。
- `contain: false` 性能更好，要求完整匹配。
- `nbt-count` 配置后，使用药水会优先扣除物品 NBT 次数。

## 药水配置

药水文件放在：

```text
plugins/AttributePotion/potion/
```

基本示例：

```yaml
测试药水:
  match: '&a测试药水'
  group: '初级药水组'
  cooldown: 30.0
  press: 0
  triggers:
    - RIGHT_CLICK_AIR
    - RIGHT_CLICK_BLOCK
  conditions:
    - '%player_level%>=10<->&c等级不足，无法使用 %id%'
  time: 10.0
  attributes:
    物理伤害: '100'
    生命力: '%player_level%*2+100'
  effects:
    NIGHT_VISION: '1<->10'
  regen:
    health: '10<->5<->2'
    mana: '10<->{time}<->1'
    hunger: '2<->5<->0'
  actions:
    success:
      - '[msg]&a成功使用 %id%'
      - '[console]give %player_name% diamond 1'
    end:
      - '[msg]&c%id% 效果结束'
    tick:
      20:
        - '[msg]&a每秒执行一次'
  distance: 10.0
  optional:
    consume: true
    cover: true
    shift: false
    death: false
    quit: false
    cool: false
    range: false
    bossbar: false
    break: false
```

### triggers

可用触发器：

```yaml
triggers:
  - ALL
  - LEFT_CLICK_AIR
  - LEFT_CLICK_BLOCK
  - RIGHT_CLICK_AIR
  - RIGHT_CLICK_BLOCK
  - KEY
```

兼容旧写法：

```yaml
- DRAGON
- GERM
- CLOUD
```

这些旧写法会统一解析为 `KEY`。

### press

`press` 只对按键触发有效：

```yaml
press: 2.5
triggers:
  - KEY
```

表示玩家需要按住按键 2.5 秒后才会使用药水。按满后服务端会自动使用，不需要等松开。

DragonCore 会收到以下同步变量：

- `apn_display`：显示名称
- `apn_progress`：按压进度，范围 `0` 到 `1`
- `apn_stop`：是否停止显示

HUD 配置文件：

```text
plugins/AttributePotion/press-hud.yml
```

### regen

恢复格式：

```text
数值<->持续秒数<->模式
```

模式：

- `0`：固定值
- `1`：当前值百分比
- `2`：最大值百分比

示例：

```yaml
regen:
  health: '10<->5<->0'
  mana: '10<->{time}<->1'
  hunger: '2<->5<->0'
```

`mana` 会自动接入 SkillAPI 或 Yetzirah。两个都存在时优先 SkillAPI。

### actions

支持动作前缀：

| 前缀 | 说明 |
| --- | --- |
| `[msg]` | 给玩家发消息 |
| `[console]` | 控制台执行命令 |
| `[op]` | 临时 OP 执行命令 |
| `[title]` | 发送标题 |
| `[actionbar]` | 发送 ActionBar |
| `[bossbar]` | 显示 BossBar |

## 数据存储

插件使用 HikariCP 管理连接。

SQLite：

```yaml
mysql:
  enable: false
  file_name: AttributePotion
```

MySQL：

```yaml
mysql:
  enable: true
  host: localhost
  port: 3306
  username: root
  password: root
  file_name: attributepotion
  table_name: data
  driver: 'com.mysql.jdbc.Driver'
  jdbc: '?useSSL=false&useUnicode=true&characterEncoding=utf8'
```

旧版兼容：

- 旧表 `data` 会被读取。
- 旧冷却表 `time_record` 会被读取。
- SQLite 旧文件 `database.db` 在未显式配置文件名时会自动优先读取。
- 新数据会写入 `<table_name>_profile`，使用 JSON 存储玩家档案。

## NBT 次数消耗

需要安装 NBTAPI。

全局配置：

```yaml
nbt-count: 'apn_count'
```

物品 NBT：

```yaml
apn_count: 10
```

当物品有该 NBT 时，使用药水会扣除 NBT 次数，而不是直接减少物品数量。建议用于不可堆叠物品。

如果药水配置：

```yaml
optional:
  break: true
```

次数耗尽时会移除该物品。

## BossBar

全局 BossBar 样式在 `config.yml`：

```yaml
bossbar:
  title: '&b%potion% &f剩余 &a%remaining%秒'
  color: BLUE
  style: SEGMENTED_10
  update-interval: 20
```

是否显示由每个药水单独决定：

```yaml
optional:
  bossbar: true
```

## 开发 API

类路径：

```java
me.chenfeng.attributepotion.api.PotionAPI
```

常用方法：

```java
PotionAPI.usePotion(player, itemStack);
PotionAPI.usePotion(player, "测试药水");
PotionAPI.forceUsePotion(player, "测试药水");
PotionAPI.addPotionTime(player, "测试药水", 10);
PotionAPI.removePotion(player, "测试药水");
PotionAPI.hasPotion(player, "测试药水");
PotionAPI.getRemainingTime(player, "测试药水");
PotionAPI.getPotionConfig("测试药水");
PotionAPI.getAllPotionConfigs();
```

## 构建

项目使用 Gradle 8.7，目标 Java 8：

```bash
./gradlew build
```

Windows：

```bat
gradlew.bat build
```

构建产物：

```text
build/libs/AttributePotion-版本号.jar
```

注意：当前 jar 会内置 HikariCP、Gson、exp4j、SQLite JDBC 和 MySQL Connector/J，因此体积较大。若只需要 SQLite 或只需要 MySQL，可以拆分构建版本减少体积。

## 注意事项

- 推荐使用 NBT 匹配药水，名称和 Lore 匹配更容易被其他插件修改影响。
- `contain: false` 性能更稳定，配置也更可控。
- 按键药水需要在 `key-slots` 中配置按键对应槽位。
- DragonCore HUD 文件会发送到客户端 `Gui/AttributePotion/press-hud.yml`，打开名为 `AttributePotion/press-hud`。
- CloudPick jar 如果是 Java 16 编译版本，不能直接在 Java 8 环境下作为编译依赖；当前项目通过反射兼容运行期调用。
