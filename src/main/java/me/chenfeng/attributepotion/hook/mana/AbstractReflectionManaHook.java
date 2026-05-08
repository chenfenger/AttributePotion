package me.chenfeng.attributepotion.hook.mana;

import me.chenfeng.attributepotion.utils.LoggerUtil;

import java.lang.reflect.Method;

/**
 * 反射型魔力 Hook 的公共工具。
 */
abstract class AbstractReflectionManaHook implements ManaHook {

    private final String name;
    protected boolean available;

    protected AbstractReflectionManaHook(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /**
     * 将反射结果转换为 double。
     *
     * @param value 反射返回值
     * @return 数值，无法转换时返回 0
     */
    protected double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 安全调用无参方法。
     *
     * @param target 调用对象
     * @param method 方法
     * @return 方法返回值，失败时返回 null
     */
    protected Object invoke(Object target, Method method) {
        try {
            return method.invoke(target);
        } catch (Throwable throwable) {
            LoggerUtil.debug("[AttributePotion] 调用 " + name + " 魔力 Hook 失败: " + throwable.getMessage());
            return null;
        }
    }

    /**
     * 安全调用带参方法。
     *
     * @param target 调用对象，静态方法传 null
     * @param method 方法
     * @param args 参数
     * @return 方法返回值，失败时返回 null
     */
    protected Object invoke(Object target, Method method, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable throwable) {
            LoggerUtil.debug("[AttributePotion] 调用 " + name + " 魔力 Hook 失败: " + throwable.getMessage());
            return null;
        }
    }
}
