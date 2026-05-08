package me.chenfeng.attributepotion.hook.attribute;

import github.saukiya.sxattribute.data.attribute.SXAttributeData;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.*;

/**
 * SX-Attribute 3.x 钩子。
 * 3.x 的 API 与 2.x 有差异，这里通过反射兼容：
 * 如反射失败，只会记录日志，不会抛出异常导致插件崩溃。
 */
public class SXAttribute3Hook extends AbstractAttributeHook {

    private static Object api;
    private static Method loadListData;
    private static Method updateData;
    private static Method setEntityAPIData;
    private final Map<UUID, Map<String, SXAttributeData>> attrMap = new HashMap<>();

    public SXAttribute3Hook() {
        try {
            Class<?> sxClass = Class.forName("github.saukiya.sxattribute.SXAttribute");
            Method getApiMethod = sxClass.getMethod("getApi");
            api = getApiMethod.invoke(null);

            Class<?> apiClass = api.getClass();

            // 3.x 中存在 loadListData(List<String>) 之类的方法
            try {
                loadListData = apiClass.getMethod("loadListData", List.class);
            } catch (NoSuchMethodException e) {
                LoggerUtil.warning("未找到 SX-Attribute 3.x 的 loadListData(List) 方法，可能当前仍为 SX2，请检查 attribute-plugin 配置。");
            }

            updateData = api.getClass().getMethod("attributeUpdate", LivingEntity.class);
            setEntityAPIData = api.getClass().getMethod("setEntityAPIData", Class.class, UUID.class, SXAttributeData.class);
        } catch (Exception e) {
            LoggerUtil.warning("SX-Attribute3 兼容失败：" + e.getMessage());
            api = null;
            loadListData = null;
            updateData = null;
            setEntityAPIData = null;
        }
    }

    @Override
    public void addAttribute(Entity entity, String source, List<String> attributes) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        if (api == null || loadListData == null || updateData == null || setEntityAPIData == null) {
            return;
        }
        if (attributes == null || attributes.isEmpty()) {
            return;
        }

        LivingEntity living = (LivingEntity) entity;
        try {
            SXAttributeData sourceData = buildAttributeData(attributes);
            attrMap.computeIfAbsent(living.getUniqueId(), uuid -> new LinkedHashMap<>())
                    .put(source, sourceData);
            updateAttributes(living);
        } catch (Exception e) {
            LoggerUtil.warning("向实体添加 SX-Attribute3 属性失败：" + e.getMessage());
        }
    }

    @Override
    public void takeAttribute(Entity entity, String source) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        if (api == null || loadListData == null || updateData == null || setEntityAPIData == null) {
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
            LoggerUtil.warning("移除 SX-Attribute3 属性失败：" + e.getMessage());
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

        setEntityAPIData.invoke(api, SXAttribute3Hook.class, living.getUniqueId(), mergedData);
        updateData.invoke(api, living);
    }

    private SXAttributeData buildAttributeData(List<String> attributes) throws Exception {
        SXAttributeData sourceData = new SXAttributeData();
        for (String attribute : attributes) {
            if (attribute == null || attribute.trim().isEmpty()) {
                continue;
            }

            SXAttributeData singleData = (SXAttributeData) loadListData.invoke(api, Collections.singletonList(attribute));
            if (singleData != null) {
                sourceData.add(singleData);
            }
        }
        return sourceData;
    }
}
