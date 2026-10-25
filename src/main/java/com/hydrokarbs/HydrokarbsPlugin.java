package com.hydrokarbs;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * 主类:退化为两行委托(18 号修正案 D12;施工计划 1.6)。
 * onEnable = {@code HydrokarbsAssembly.assemble(this)} 存 {@link HydrokarbsAssembly.Context};
 * onDisable = {@code HydrokarbsAssembly.shutdown(ctx)}。装配序列全部住在装配类,
 * 主类保持纯净;装配失败自 assemble 上抛 → 服务端禁用本插件(不吞、不半启动)。
 */
public final class HydrokarbsPlugin extends JavaPlugin {

    private HydrokarbsAssembly.Context context;

    @Override
    public void onEnable() {
        context = HydrokarbsAssembly.assemble(this);
        getLogger().info("Hydrokarbs enabled");
    }

    @Override
    public void onDisable() {
        if (context != null) {
            HydrokarbsAssembly.shutdown(context);
            context = null;
        }
    }
}
