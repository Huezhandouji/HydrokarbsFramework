package com.hydrokarbs.engine;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionService;

/**
 * 全局节拍器:单一周期任务驱动全部会话(12-代码骨架 §12.2 第三个代码块)。
 *
 * <p>★ 每实例一个周期任务是事故源(14-陷阱清单 §14.6)——全局唯一本类一个句柄,
 * onDisable 必取消,否则重载后任务翻倍;组件级「稍后执行」走实例端口的
 * later 登记,不在本类开口。
 *
 * <p>★ 切换的唯一驱动点是 {@link #tickAll()} 里的
 * {@code runPendingSwitch()}(03-会话与模式切换 §3.5 防重入的机械保证)——
 * 「事件栈内不发切换」从本阶段起就是纪律。★v4(2.6):每会话驱动序扩为<b>四步</b>
 * (新增 ③ 会话组件派发;空场计时移出内核 ④,§3.8)——<b>顺序是规格的一部分</b>:
 * ① 待处理切换 → ② 当前实例派发 → ③ 会话组件派发(每 tick 必调,与实例有无无关)
 * → ④ 内核清点(断线宽限等)。
 */
public final class EngineTicker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EngineTicker.class.getName());

    /** 初始延迟(tick):★ 必须 &gt; 0,写 0 调度器抛 IAE(14-陷阱清单 §14.6)。 */
    private static final long INITIAL_DELAY_TICKS = 1L;

    private final Plugin plugin;
    private final SessionService sessions;
    private volatile ScheduledTask handle;

    public EngineTicker(Plugin plugin, SessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * 启动全局单任务(版本基线:全局区域调度器,Folia 迁移成本 0,§15.3)。
     * ★ {@code runAtFixedRate} 的函数形参是 {@code Consumer<ScheduledTask>},
     * 无参方法引用无法适配该签名,必须写 {@code task -> tickAll()}。
     * periodTicks &lt; 1 抛 {@link IllegalArgumentException}(调度器同样要求为正)。
     */
    public void start(long periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("periodTicks 必须 >= 1: " + periodTicks);
        }
        cancelCurrent();
        handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, task -> tickAll(), INITIAL_DELAY_TICKS, periodTicks);
    }

    /**
     * 驱动全部会话一拍(2.3 起提 public:会话层离线测试直调驱动,与 Bukkit 接线分离;
     * 自动切换的调用栈检查以本类帧为准,见 {@code SessionServiceImpl} 的防护闸门)。
     * ★v4(2.6):每会话四步,<b>顺序是规格的一部分</b>(§3.8/§11.4):
     * ① 待处理切换(机制,不变)→ ② 当前实例派发(若有实例;不变)→
     * ③ <b>会话组件派发(每 tick 必调,与实例有无无关;切换间隙照常——实例侧组件
     * 不 tick、会话组件照常 tick;组件级异常在 ③ 内部按 D9 隔离,不外抛)</b> →
     * ④ 内核清点(断线宽限等;机制,不变——空场计时已下放组件,D11)。
     * 逐会话 try/catch——一个坏会话被隔离,不拖死别的会话。
     */
    public void tickAll() {
        for (Session session : sessions.all()) {
            try {
                session.runPendingSwitch();          // ① 切换只在此驱动(§3.5)
                session.tickCurrentInstance();       // ② 当前实例派发(只派发组件更新)
                session.tickSessionComponents();     // ③ 会话组件派发(★v4;每 tick 必调)
                session.idleTimers();                // ④ 内核节律(宽限清点;空场已下放)
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "会话驱动异常(隔离该会话,其余照常): " + session.id().value(), t);
                session.quarantine();
            }
        }
    }

    /** 取消句柄(★ onDisable 必调;全部会话的解散接线在 L0 关停序列,§12.2)。 */
    @Override
    public void close() {
        cancelCurrent();
    }

    private void cancelCurrent() {
        ScheduledTask current = handle;
        if (current != null) {
            current.cancel();
            handle = null;
        }
    }
}
