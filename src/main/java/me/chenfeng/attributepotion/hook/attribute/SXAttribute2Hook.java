package me.chenfeng.attributepotion.hook.attribute;

import github.saukiya.sxattribute.SXAttribute;
import github.saukiya.sxattribute.api.SXAPI;
import github.saukiya.sxattribute.data.attribute.SXAttributeData;
import github.saukiya.sxattribute.data.condition.SXConditionType;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.*;

/**
 * SX-Attribute 2.x 钩子。
 *
 * 使用反射调用 2.x API：
 * - getLoreData(LivingEntity, SXConditionType, List<String>)
 * - setEntityAPIData(Class, UUID, SXAttributeData)
 * - updateStats(LivingEntity)
 *
 * 如果服务器实际加载的是 3.x，将在构造时给出提示，避免误用。
 */
public class SXAttribute2Hook extends AbstractAttributeHook {

    // 存储 API 实例
    private Object api;
    private Method getSXAttributeData;
    private Method updateData;
    private Method setEntityAPIData;
    private final Map<UUID, Map<String, SXAttributeData>> attrMap = new HashMap<>();

    public SXAttribute2Hook() {
        try {
            Class<?> sxClass = Class.forName("github.saukiya.sxattribute.SXAttribute");
            Method getApiMethod = sxClass.getMethod("getApi");
            api = getApiMethod.invoke(null);

            // 如果存在 loadListData(List) 说明是 3.x，提醒用户配置错误
            try {
                api.getClass().getMethod("loadListData", List.class);
                LoggerUtil.warning("检测到 SX-Attribute 3.x，请在 config.yml 把 attribute-plugin 改为 SXAttribute3！");
            } catch (NoSuchMethodException ignored) {
                // 正常情况：2.x 没有该方法
            }

            getSXAttributeData = api.getClass().getMethod("getLoreData", LivingEntity.class, SXConditionType.class, List.class);
            updateData = api.getClass().getMethod("updateStats", LivingEntity.class);
            setEntityAPIData = api.getClass().getMethod("setEntityAPIData", Class.class, UUID.class, SXAttributeData.class);
        } catch (Exception e) {
            LoggerUtil.warning("SX-Attribute2 兼容失败：" + e.getMessage());
            api = null;
            getSXAttributeData = null;
            updateData = null;
            setEntityAPIData = null;
        }
    }

    @Override
    public void addAttribute(Entity entity, String source, List<String> attributes) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        if (api == null || getSXAttributeData == null || updateData == null || setEntityAPIData == null) {
            return;
        }
        if (attributes == null || attributes.isEmpty()) {
            return;
        }


        LivingEntity living = (LivingEntity) entity;
        try {
            SXAttributeData sourceData = buildAttributeData(living, attributes);
            attrMap.computeIfAbsent(living.getUniqueId(), uuid -> new LinkedHashMap<>())
                    .put(source, sourceData);
            updateAttributes(living);
        } catch (Exception e) {
            LoggerUtil.warning("向实体添加 SX-Attribute2 属性失败：" + e.getMessage());
        }
    }

    @Override
    public void takeAttribute(Entity entity, String source) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        if (api == null || getSXAttributeData == null || updateData == null || setEntityAPIData == null) {
            return;
        }

        LivingEntity living = (LivingEntity) entity;
        try {
            Map<String, SXAttributeData> entityAttributes = attrMap.get(living.getUniqueId());
            if (entityAttributes != null) {
                entityAttributes.remove(source);
                if (entityAttributes.isEmpty()) {
                    attrMap.remove(living.getUniqueId());
                }
            }
            updateAttributes(living);
        } catch (Exception e) {
            LoggerUtil.warning("移除 SX-Attribute2 属性失败：" + e.getMessage());
        }
    }

    private void updateAttributes(LivingEntity living) throws Exception {
        SXAttributeData mergedData = new SXAttributeData();
        Map<String, SXAttributeData> entityAttributes = attrMap.get(living.getUniqueId());
        if (entityAttributes != null) {
            for (SXAttributeData data : entityAttributes.values()) {
                if (data != null) {
                    mergedData.add(data);
                }
            }
        }

        setEntityAPIData.invoke(api, SXAttribute2Hook.class, living.getUniqueId(), mergedData);
        updateData.invoke(api, living);
    }

    private SXAttributeData buildAttributeData(LivingEntity living, List<String> attributes) throws Exception {
        SXAttributeData sourceData = new SXAttributeData();
        for (String attribute : attributes) {
            if (attribute == null || attribute.trim().isEmpty()) {
                continue;
            }

            SXAttributeData singleData = (SXAttributeData) getSXAttributeData.invoke(api, living, null, Collections.singletonList(attribute));
            if (singleData != null) {
                sourceData.add(singleData);
            }
        }
        return sourceData;
    }
}
