package com.hydrokarbs.command;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.port.ModeLookup;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeSelection;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.engine.session.SwitchResult;

/**
 * 主命令(根命令 {@code hydrokarbs},别名 {@code hk}; Brigadier 注册于
 * {@code LifecycleEvents.COMMANDS},经主类 {@code getLifecycleManager} 挂接——
 * 装配产物先于命令构造,闭包捕获会话服务)。
 *
 * <p>本阶段子命令:
 * <ul>
 *   <li>{@code switch <mode> [map] [force]}——切换目标模式(§3.2 触发来源①:
 *       外部调用,可带豁免标记);{@code mode} 参数建议来自模式注册表全量 id;
 *       权限 {@code hydrokarbs.command.switch};拒绝回显 detail(人读清单),
 *       成功回显 from→to;</li>
 *   <li>{@code session}——列出会话与当前模式。</li>
 * </ul>
 *
 * <p><b>命令体零业务分支</b>(L0 判据):只做参数装配后委托 {@link SessionService};
 * 切换的目标会话解析:发送者玩家所属会话优先,否则恰有唯一存活会话时取之。
 */
public final class MatchCommand {

    /** 切换子命令的权限节点。 */
    public static final String SWITCH_PERMISSION = "hydrokarbs.command.switch";

    private static final String ROOT = "hydrokarbs";
    private static final List<String> ALIASES = List.of("hk");

    private MatchCommand() {
    }

    /** 在主类的命令生命周期事件上注册命令树(重载后自动重新注册)。 */
    public static void register(JavaPlugin plugin, SessionService sessions, ModeLookup modes) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(buildRoot(sessions, modes), "Hydrokarbs 主命令", ALIASES));
    }

    /** 组命令树(包内可见:离线断言树形用;不触 Bukkit 静态)。 */
    static LiteralCommandNode<CommandSourceStack> buildRoot(SessionService sessions, ModeLookup modes) {
        return Commands.literal(ROOT)
                .then(buildSwitch(sessions, modes))
                .then(buildSession(sessions))
                .build();
    }

    /** {@code switch <mode> [map] [force]}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildSwitch(SessionService sessions, ModeLookup modes) {
        return Commands.literal("switch")
                .requires(source -> source.getSender().hasPermission(SWITCH_PERMISSION))
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (MatchMode mode : modes.all()) {
                                builder.suggest(mode.id());
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> executeSwitch(
                                context.getSource(), sessions, context.getArgument("mode", String.class), null, false))
                        .then(Commands.argument("map", StringArgumentType.word())
                                .executes(context -> executeSwitch(
                                        context.getSource(), sessions,
                                        context.getArgument("mode", String.class),
                                        context.getArgument("map", String.class), false))
                                .then(Commands.argument("force", BoolArgumentType.bool())
                                        .executes(context -> executeSwitch(
                                                context.getSource(), sessions,
                                                context.getArgument("mode", String.class),
                                                context.getArgument("map", String.class),
                                                context.getArgument("force", Boolean.class))))));
    }

    /** {@code session}:列出会话与当前模式。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildSession(SessionService sessions) {
        return Commands.literal("session").executes(context -> {
            CommandSender sender = context.getSource().getSender();
            var alive = sessions.all();
            if (alive.isEmpty()) {
                sender.sendMessage(Component.text("当前没有存活会话", NamedTextColor.YELLOW));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
            sender.sendMessage(Component.text("存活会话 " + alive.size() + " 个:", NamedTextColor.AQUA));
            for (Session session : alive) {
                String currentMode = session.currentInstance()
                        .map(instance -> instance.modeId())
                        .orElse("(无实例)");
                sender.sendMessage(Component.text("  " + session.id().value()
                        + " → " + currentMode + "(成员 " + session.roster().size() + ",状态 "
                        + session.state() + ")", NamedTextColor.GRAY));
            }
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });
    }

    /** 切换执行:装配参数 → 委托会话服务 → 回显结果(future 完成点在主线程)。 */
    private static int executeSwitch(CommandSourceStack source, SessionService sessions,
                                     String modeId, String mapId, boolean force) {
        CommandSender sender = source.getSender();
        Optional<SessionId> target = resolveTargetSession(source, sessions);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("无法确定目标会话(发送者不在会话且存活会话数不为 1)", NamedTextColor.RED));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        MatchModeSelection selection = new MatchModeSelection(
                modeId, Optional.ofNullable(mapId), force);
        CompletableFuture<SwitchResult> future = sessions.switchMode(target.get(), selection, Handoff.empty());
        future.whenComplete((result, err) -> {
            if (err != null) {
                sender.sendMessage(Component.text("切换异常: " + err, NamedTextColor.RED));
                return;
            }
            switch (result) {
                case SwitchResult.Succeeded ok -> sender.sendMessage(Component.text(
                        "切换完成: " + ok.fromMode() + " → " + ok.toMode()
                                + "(会话 " + ok.session().value() + ")", NamedTextColor.GREEN));
                case SwitchResult.Rejected rejected -> sender.sendMessage(Component.text(
                        "切换被拒[" + rejected.code() + "]: " + rejected.detail(), NamedTextColor.RED));
            }
        });
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** 目标会话解析:发送者为玩家时其所属会话优先;否则恰有唯一存活会话时取之。 */
    private static Optional<SessionId> resolveTargetSession(CommandSourceStack source, SessionService sessions) {
        if (source.getExecutor() instanceof Player player) {
            Optional<Session> owning = sessions.sessionOf(player.getUniqueId());
            if (owning.isPresent()) {
                return Optional.of(owning.get().id());
            }
        }
        var alive = sessions.all();
        return alive.size() == 1 ? Optional.of(alive.iterator().next().id()) : Optional.empty();
    }
}
