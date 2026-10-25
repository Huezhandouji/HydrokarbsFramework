package com.hydrokarbs.testkit;

import java.lang.reflect.Proxy;

import org.bukkit.plugin.Plugin;

/**
 * 测试用插件身份键(test 源集,7.5):{@code Plugin} 动态代理——注册式通道的
 * 提供方参数在离线测试里以代理占位(镜像 {@link FakeWorldPort#proxyWorld} 的
 * 「测试类型签名引用 Bukkit 类型、传代理不碰 Bukkit 静态」先例;testRuntimeOnly
 * 有 paper-api,类型可加载)。只作身份键/日志名使用,不调任何 Bukkit 行为。
 */
public final class TestPlugins {

    private TestPlugins() {
    }

    /** 具名插件代理(同名不同实例仍按身份区分——注册表按实例身份记归属)。 */
    public static Plugin named(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
