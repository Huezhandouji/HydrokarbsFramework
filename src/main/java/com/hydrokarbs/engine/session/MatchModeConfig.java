package com.hydrokarbs.engine.session;

import java.util.List;

/**
 * 单次模式运行的只读配置视图(施工计划 1.2 立最小口、2.2 扩全口;15-适配 §15.2)。
 *
 * <p><b>落位注记</b>:12-代码骨架 §12.1 未列本类型;按消费方(聚合根工厂
 * {@code MatchModeProvider#build} 与注册表装配)就近置于 {@code engine/session/}
 * (1.2 原落位沿用,施工计划 2.2 登记过裁决)。实现体 = L1
 * {@code bridge/config/MatchModeConfigLoader}(读 {@code modes/<id>.yml})。
 *
 * <p><b>容错契约(接口契约的一部分,15-适配 §15.2 铁律 1/2)</b>:
 * <ul>
 *   <li><b>缺键必须有默认值</b>——每个取值方法都带 {@code def} 形参,
 *       path 不存在时返回 def,绝不抛、绝不裸取;</li>
 *   <li><b>非法值降级 + 警告,不崩</b>——值类型与目标类型不符时返回 def
 *       并记 WARNING 日志(实现体职责),不中断装载。</li>
 * </ul>
 *
 * <p>成员按消费方(各通用组件与模式工厂)扩充、只增不改。
 */
public interface MatchModeConfig {

    /**
     * 读整数配置项:path 不存在或不可解析为整数时返回 def(并按容错契约记警告)。
     * 用法示例(通用组件侧):{@code cfg.getInt("vote-seconds", 30)}。
     */
    int getInt(String path, int def);

    /**
     * 读字符串配置项:path 不存在或值非标量(集合/映射)时返回 def。
     * 用法示例:{@code cfg.getString("kick-message", "已离场")}。
     */
    String getString(String path, String def);

    /**
     * 读字符串列表配置项:path 不存在返回 def;值为列表时逐元素收为字符串;
     * 值为标量时降级为单元素列表(铁律 2:不因形状不符而崩)。
     */
    List<String> getStringList(String path, List<String> def);

    /**
     * 列出某节下的全部子键(保持文件声明序)。section 不存在或不是映射节时返回空列表。
     * 用法示例:{@code cfg.keys("vote-pool")}(遍历投票池条目键)。
     */
    List<String> keys(String section);
}
