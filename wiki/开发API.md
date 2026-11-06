# 开发 API

把插件 jar 作为 `compileOnly` 依赖，并在 `plugin.yml` 里 `softdepend: [AttributePotion]`。

## PotionAPI

`me.chenfeng.attributepotion.api.PotionAPI`，所有方法都是静态的，应在主线程调用。

| 方法 | 说明 |
| --- | --- |
| `boolean usePotion(Player, ItemStack)` | 匹配物品并走完整流程。不扣除物品，由调用方决定 |
| `boolean usePotion(Player, String)` | 按节点走完整流程 |
| `boolean forceUsePotion(Player, String)` | 跳过条件、冷却和 `PotionUseEvent` |
| `boolean addPotionTime(Player, String, double)` | 增减剩余秒数。永久药水和未激活药水返回 `false` |
| `boolean removePotion(Player, String)` | 结束药水，执行 `end` 动作并触发移除事件 |
| `boolean hasPotion(Player, String)` | 是否激活 |
| `double getRemainingTime(Player, String)` | 剩余秒数，永久返回 `-1`，未激活返回 `0` |
| `ActivePotion getActivePotion(Player, String)` | 运行时对象，未激活返回 `null` |
| `PotionConfig getPotionConfig(String)` | 配置，不存在返回 `null` |
| `Map<String, PotionConfig> getAllPotionConfigs()` | 所有配置，不可修改 |

玩家刚进服、数据还没读完时，`usePotion` 返回 `false`，`forceUsePotion` 不受影响。

## 事件

都继承 `PotionEvent`（`PlayerEvent`），可通过 `getPotionConfig()` 取配置。

| 事件 | 时机 | 可取消 |
| --- | --- | --- |
| `PotionUseEvent` | 通过全部检查后、应用效果前。`force` 和范围波及不触发 | 是 |
| `PotionApplyEvent` | 效果应用、`success` 动作执行后。范围波及也触发 | 否 |
| `PotionRemoveEvent` | 药水结束时，`end` 动作执行后。离线到期的药水在下次进服时补发，关闭 `run-end-on-offline-expire` 时不发 | 否 |

`PotionApplyEvent` 和 `PotionRemoveEvent` 可通过 `getActivePotion()` 取运行时对象。

玩家正常下线和关服时药水只是存档，不触发 `PotionRemoveEvent`。

```java
@EventHandler
public void onUse(PotionUseEvent event) {
    if (event.getPotionConfig().getKey().equals("力量药水") && inDuel(event.getPlayer())) {
        event.setCancelled(true);
    }
}
```
