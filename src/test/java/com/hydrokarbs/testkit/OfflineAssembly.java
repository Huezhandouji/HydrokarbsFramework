package com.hydrokarbs.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.hydrokarbs.engine.component.MatchModeComponent;
import com.hydrokarbs.engine.instance.MatchModeInstance;
import com.hydrokarbs.engine.mode.MatchMode;
import com.hydrokarbs.engine.mode.MatchModeMeta;
import com.hydrokarbs.engine.port.EngineServices;
import com.hydrokarbs.engine.port.PlatformPort;
import com.hydrokarbs.engine.session.EndResult;
import com.hydrokarbs.engine.session.Handoff;
import com.hydrokarbs.engine.session.MatchModeConfig;
import com.hydrokarbs.engine.session.Session;
import com.hydrokarbs.engine.session.SessionId;
import com.hydrokarbs.engine.session.SessionMember;
import com.hydrokarbs.engine.session.SessionRoster;
import com.hydrokarbs.map.MapDefinition;

/**
 * 离线装配工具(施工计划 1.5「离线装配的最小假件清单」,test 源集):
 * 测试地图、Map 背后的模式参数测试实现、会话测试桩、假平台、模式拼装便捷口。
 * 全部只依赖引擎公开面;零 Bukkit import(Plugin 位置以全限定名返回 null)。
 */
public final class OfflineAssembly {

    private OfflineAssembly() {
    }

    /** 测试地图(最小声明)。 */
    public static MapDefinition map() {
        return new MapDefinition("test_map", "test_world", java.util.Set.of(), Map.of());
    }

    /** Map 背后的模式参数测试实现(缺路径回落 def)。 */
    public static MatchModeConfig cfg(Map<String, Integer> values) {
        return (path, def) -> values.getOrDefault(path, def);
    }

    /** 全回落缺省的模式参数。 */
    public static MatchModeConfig cfg() {
        return (path, def) -> def;
    }

    /** 记录结束请求的会话测试桩(覆写非 final 方法)。 */
    public static final class RecordingSession extends Session {

        /** 结束请求记录:(实例 id 尾段, 去向描述)。 */
        public final List<String> endNotices = new ArrayList<>();
        /** 原始结束请求记录(实例, 结果)。 */
        public final List<Map.Entry<MatchModeInstance, EndResult>> rawEndNotices = new ArrayList<>();

        public RecordingSession(String id, SessionMember... members) {
            super(new SessionId(id), new SessionRoster(List.of(members)));
        }

        @Override
        public void instanceEnded(MatchModeInstance instance, EndResult result) {
            endNotices.add(instance.id().value() + "/"
                    + (result.transition().isEmpty() ? "nowhere" : "intent:" + describe(result)));
            rawEndNotices.add(Map.entry(instance, result));
        }

        private static String describe(EndResult result) {
            return result.transition().map(t -> t.target().modeId()).orElse("?");
        }
    }

    /** 空成员会话桩。 */
    public static RecordingSession session(String id) {
        return new RecordingSession(id);
    }

    /** 平台假实现:记录并即时执行一次性任务;句柄 close 即计数。 */
    public static final class FakePlatform implements PlatformPort {

        /** later→scheduleOnce 的延迟记录。 */
        public final List<Long> delays = new ArrayList<>();
        /** 已执行的任务快照。 */
        public final List<Runnable> tasks = new ArrayList<>();
        /** 句柄 close 次数(close 统一回收的外显)。 */
        public int cancels;

        @Override
        public Logger logger() {
            return Logger.getLogger("test-platform");
        }

        @Override
        public org.bukkit.plugin.Plugin plugin() {   // Bukkit 位置:纯接口,返回 null,不 import
            return null;
        }

        @Override
        public AutoCloseable scheduleOnce(long delayTicks, Runnable task) {
            delays.add(delayTicks);
            tasks.add(task);
            task.run();
            return () -> cancels++;
        }
    }

    /** 四件套拼装便捷口:按传入顺序 addComponent(声明序 = 入表序)。 */
    public static MatchMode mode(String modeId, LinkedHashMap<String, MatchModeComponent.Specification<?>> specs) {
        MatchMode.Builder builder = new MatchMode.Builder(modeId)
                .meta(new MatchModeMeta(java.util.Set.of(), false, 0, 100,
                        MatchModeMeta.DefaultTransition.STAY));
        specs.forEach(builder::addComponent);
        return builder.build();
    }

    /** 造实例(真容器;awakeAll/startAll 为包私有,调用方须在 engine.instance 包)。 */
    public static MatchModeInstance instance(MatchMode mode, RecordingSession session, FakePlatform platform) {
        return mode.createInstance(session, map(), cfg(), null, Handoff.empty(), new EngineServices(platform));
    }
}
