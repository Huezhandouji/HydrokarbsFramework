package com.hydrokarbs.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * 子命令贡献口:根命令 {@code /hydrokarbs}(别名 {@code hk})下的子树构造器
 * (扩展接入点 seam,取代「在既有命令类上加子命令路由」的旧写法;本次命令系统
 * 重做为非冻结语义变更、未立修正案,故不标版本号——溯源以版本账本与 git 史为准)。
 *
 * <p><b>接入用法——同 jar 新增一个命令的完整动作(两步,既有命令类内部逻辑零改动)</b>:
 * <ol>
 *   <li>新建自己的命令类({@code final},构造器捕获所需依赖——{@code SessionService}/
 *       {@code ConfigView} 等),实现 {@link #node()} 返回自己的子树 builder,
 *       自带 {@code .requires} 权限门({@code hydrokarbs.command.*});</li>
 *   <li>在 {@code HydrokarbsAssembly.assemble} 第⑬步
 *       {@code HydrokarbsCommand.register(plugin, List.of(new SessionCommand(...)))}
 *       的装配列表里追加一行 {@code new XxxCommand(依赖)}。</li>
 * </ol>
 * 已点名的未来消费者:Config/Debug/Reload 命令(12-代码骨架 §12.1 愿景)、
 * vote 子命令(阶段4 4.4)、SHDF 三玩法子命令(2.5)。
 *
 * <p><b>限界(形态 A/B,AGENTS §4.11)</b>:本 seam 仅限<b>同 jar 装配期</b>
 * (形态 A 的 L0 装配豁免)。Paper Brigadier 每次 {@code registrar().register(...)}
 * 交付整棵树,形态 B(双 jar)下宿主/第三方无法把子命令嫁接到框架
 * {@code /hydrokarbs} 根——跨 jar 消费者一律<b>自立根命令</b>(自有
 * {@code LifecycleEvents.COMMANDS} handler + 自有权限节点),不经本 seam;
 * 全新的根命令同样按该模式自立类,不纳入本 seam。
 */
public interface Subcommand {

    /** 返回子树 builder(由 {@code HydrokarbsCommand#buildRoot} 挂到根下;权限门自带)。 */
    LiteralArgumentBuilder<CommandSourceStack> node();
}
