package com.hydrokarbs;

import java.util.List;
import java.util.Map;

import org.bukkit.plugin.java.JavaPlugin;

import com.hydrokarbs.engine.EngineTicker;
import com.hydrokarbs.engine.event.SessionEventBus;
import com.hydrokarbs.engine.port.ConfigView;
import com.hydrokarbs.engine.service.ServiceRegistry;
import com.hydrokarbs.engine.service.ServiceSpec;
import com.hydrokarbs.engine.session.CapabilityRegistry;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionService;
import com.hydrokarbs.service.GlobalBoard;
import com.hydrokarbs.service.GlobalLedger;
import com.hydrokarbs.util.Threads;

/**
 * L0 装配编排:单一静态装配类 + 单一编排函数(18 号修正案 D12;施工计划 1.6)。
 *
 * <p>{@link #assemble} 一函承载 onEnable 全部装配序列——线性装配语句,自上而下可读完;
 * {@link #shutdown} 镜像 onDisable。主类退化为两行委托({@code HydrokarbsPlugin}
 * 存本方法返回的 {@link Context},onDisable 转交 shutdown)。
 *
 * <p>D12 纪律:①静态类可有静态函数,<b>不许有静态可变状态</b>——装配产物只经
 * 返回值交付(插件重载安全);②assemble 只含线性装配语句(本阶段无拓扑/配置分支),
 * 超一屏时抽私有分段 helper,不复活多个公共装配类;③命名不叫 {@code Bootstrapper}
 * (§12.1 已有 Paper 引导占位类)。
 *
 * <p><b>本阶段最小接线</b>(阶段 2 未交付类型以最小壳/占位支撑「样例编译通过 +
 * 起服冒烟」,正式接线随阶段 2 落地——1.6 文档「关键实现要点」):
 * <ul>
 *   <li>配置:{@link ConfigView} 以全回落缺省壳占位(L1 {@code ConfigRepository}
 *       归 2.2);</li>
 *   <li>会话服务:空集合壳(2.3 立出完整实现时整体替换);</li>
 *   <li>会话事件:本装配 new 一条 {@link SessionEventBus} 作为服务订阅面汇聚点
 *       (会话侧向它转发会话事件的接线归阶段 2,本阶段无人发布、订阅空转);</li>
 *   <li>服务声明顺序随意(拓扑序由 seal 保证),本样例按账本 → 看板线性列出。</li>
 * </ul>
 *
 * <p>seal 失败处理:装配异常自 assemble 上抛 → onEnable 异常 → 服务端记录严重
 * 日志并禁用插件(等效「SEVERE + setEnabled(false)」)——不吞、不半启动。
 */
public final class HydrokarbsAssembly {

    private HydrokarbsAssembly() {
    }

    /**
     * 装配产物(不可变 record,由插件实例字段持有)。
     * 阶段 2 增量:会话服务本体(届时 dissolve 全部会话入 shutdown 序);
     * 阶段 7 增量:公共 API 面与其注册(注销入 shutdown 序)。
     */
    public record Context(ServiceRegistry services, EngineTicker ticker) {
    }

    /** onEnable 全部装配序列(线性;失败上抛 = 插件拒载)。 */
    public static Context assemble(JavaPlugin plugin) {
        Threads.init(plugin);                                  // ① 平台回程口(既有)
        var capabilities = new CapabilityRegistry();           // ② 能力标签(占位壳,2.3 接管)
        var sessions = new EmptySessionService();              // ③ 会话服务最小壳(2.3 接管)
        var sessionEvents = new SessionEventBus();             // ④ 服务订阅面汇聚点(2 接线转发)
        ConfigView cfg = ConfigView.fallbackView();            // ⑤ 配置占位(2.2 接管)
        var services = new ServiceRegistry(capabilities, plugin, cfg);   // ⑥ 服务注册表
        services.install(ServiceSpec.of("global-ledger", GlobalLedger.class,
                        env -> new GlobalLedger(sessionEvents, sessions, session -> Map.of()))
                .capabilities("global-ledger")
                .build());
        services.install(ServiceSpec.of("global-board", GlobalBoard.class,
                        env -> new GlobalBoard(sessionEvents))
                .capabilities("global-board")
                .build());
        services.seal();                                       // ⑦ 校验 + 拓扑 + 启动 + 标签
        var ticker = new EngineTicker(plugin, sessions);       // ⑧ 全局节拍(周期硬编码 1,读配置归 2)
        ticker.start(1L);
        // ⑨ 阶段 7 占位:公共 API 面构造 + ServicesManager 注册
        return new Context(services, ticker);
    }

    /** onDisable 镜像:先停节拍,再逆序关停服务(异常隔离 + 摘标签),最后收执行器。 */
    public static void shutdown(Context ctx) {
        ctx.ticker().close();           // ★ 必取消,否则重载后任务翻倍(14.6)
        // 阶段 2 占位:dissolve 全部会话(介于节拍与关停之间)
        ctx.services().shutdown();      // 逆序 safeStop + 幂等 + 摘能力标签(★38)
        Threads.shutdown();
        // 阶段 7 占位:公共 API 注销(ServicesManager unregister)
    }

    /**
     * 会话服务最小壳(原主类内同名壳迁入;2.3 立出完整实现时整体替换):
     * 会话集合恒空,节拍器空转。
     */
    private static final class EmptySessionService implements SessionService {

        @Override
        public List<Session> all() {
            return List.of();
        }
    }
}
