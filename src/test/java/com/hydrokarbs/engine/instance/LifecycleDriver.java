package com.hydrokarbs.engine.instance;

/**
 * 生命周期驱动口(test 源集):装配二段 {@code awakeAll}/{@code startAll}
 * 为包私有(「离线测试同包直调」是 1.3 既定口径),本类在同一包内代调,
 * 供其他包的集成测试驱动真容器——它自身只依赖引擎类型,
 * 不触发「引擎不得依赖组件」的分层冻结(★1 扫 classpath 含测试类)。
 */
public final class LifecycleDriver {

    private LifecycleDriver() {
    }

    /** 装配二段:先 awake(只解析依赖)再 start(玩家可见副作用,至多一次)。 */
    public static void begin(MatchModeInstance instance) {
        instance.awakeAll();
        instance.startAll();
    }
}
