package com.hydrokarbs.engine.session;

/**
 * 单次模式运行的只读配置视图(施工计划 1.2;装载归阶段 2)。
 *
 * <p>成员按消费方(阶段 2 的 {@code modes/<id>.yml} 装载与各通用组件)扩充、
 * 只增不改;实现体与配置文件装载归阶段 2(2.2),测试侧以 Map 背后的
 * 测试实现替代(1.4)。本阶段只立接口。
 */
public interface MatchModeConfig {

    /**
     * 读整数配置项:path 不存在或不可解析时返回 def。
     * 用法示例(通用组件侧):{@code cfg.getInt("vote-seconds", 30)}。
     */
    int getInt(String path, int def);
}
