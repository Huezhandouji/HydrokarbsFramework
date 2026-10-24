package com.hydrokarbs.component.rule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hydrokarbs.engine.component.ComponentRegistry;
import com.hydrokarbs.engine.event.MatchModeInstanceEvent;
import com.hydrokarbs.engine.port.MatchModeInstanceServices;
import com.hydrokarbs.testkit.FakeSelfPort;

/**
 * 规则簿离线求值(施工计划 1.5;16-路线图与验收 §16.2-★9:不启动服务端即可跑):
 * trigger 不匹配不执行 when / trigger 匹配但 when 拒绝不执行 then /
 * then 异常不阻断后续 / 多条规则声明序 / eventAs 类型取回。
 * 事件类型为测试内自定义 record(零 Bukkit import,连伤害原因枚举都不用)。
 */
class RuleBookComponentTest {

    // ── 测试自定义事件(事件词汇跟发布者走的消费者侧演练) ──────────

    record Ping(int tick, long occurredAtMillis, String tag) implements MatchModeInstanceEvent {
    }

    record Pong(int tick, long occurredAtMillis, int amount) implements MatchModeInstanceEvent {
    }

    // ── 装配工具 ────────────────────────────────────────────────

    private static RuleBookComponent book(List<Rule> rules) {
        FakeSelfPort self = new FakeSelfPort();
        MatchModeInstanceServices services =
                new MatchModeInstanceServices(self, new ComponentRegistry(), null);
        return new RuleBookComponent.Spec(rules).create("rules", services);
    }

    private static Ping ping(String tag) {
        return new Ping(1, System.currentTimeMillis(), tag);
    }

    // ── 用例 ────────────────────────────────────────────────────

    @Test
    void triggerMismatchSkipsWhenAndThen() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(new Rule("r1",
                ev -> ev instanceof Ping,
                ctx -> {
                    log.add("when");
                    return true;
                },
                ctx -> log.add("then"))));

        assertDoesNotThrow(() -> book.onEvent(new Pong(1, System.currentTimeMillis(), 7)));
        assertTrue(log.isEmpty(), "trigger 不匹配:when/then 都不执行");
    }

    @Test
    void whenDenialSkipsThen() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(new Rule("r1",
                ev -> ev instanceof Ping,
                ctx -> {
                    log.add("when");
                    return false;    // 拒绝
                },
                ctx -> log.add("then"))));

        book.onEvent(ping("x"));
        assertEquals(List.of("when"), log, "when 被求值但拒绝:then 不执行");
    }

    @Test
    void thenThrowDoesNotBlockLaterRules() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(
                new Rule("bad",
                        ev -> ev instanceof Ping,
                        ctx -> true,
                        ctx -> {
                            throw new RuntimeException("boom");
                        }),
                new Rule("good",
                        ev -> ev instanceof Ping,
                        ctx -> true,
                        ctx -> log.add("good:fired"))));

        assertDoesNotThrow(() -> book.onEvent(ping("x")), "坏规则被逐条隔离,不逃逸");
        assertEquals(List.of("good:fired"), log, "then 异常不阻断后续规则");
    }

    @Test
    void rulesFireInDeclarationOrder() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(
                new Rule("first", ev -> ev instanceof Ping, ctx -> true, ctx -> log.add("1")),
                new Rule("second", ev -> ev instanceof Ping, ctx -> true, ctx -> log.add("2")),
                new Rule("third", ev -> ev instanceof Ping, ctx -> true, ctx -> log.add("3"))));

        book.onEvent(ping("x"));
        assertEquals(List.of("1", "2", "3"), log, "求值序 = 声明序");
    }

    @Test
    void eventAsRetrievesTypedEvent() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(new Rule("typed",
                ev -> ev instanceof Ping,
                ctx -> "wanted".equals(ctx.eventAs(Ping.class).tag()),
                ctx -> log.add(ctx.eventAs(Ping.class).tag()))));

        book.onEvent(ping("other"));
        assertTrue(log.isEmpty(), "when 经 eventAs 读到载荷并拒绝");

        book.onEvent(ping("wanted"));
        assertEquals(List.of("wanted"), log, "then 经 eventAs 类型化取回事件载荷");
    }

    @Test
    void eventAsTypeMismatchFailsLoudly() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(new Rule("bad-cast",
                ev -> ev instanceof Ping,
                ctx -> true,
                ctx -> log.add(String.valueOf(ctx.eventAs(Pong.class))))));

        // 类型不符在 then 内抛 ClassCastException —— 被逐条隔离吞掉并 WARN,不逃逸、不留痕
        assertDoesNotThrow(() -> book.onEvent(ping("x")));
        assertTrue(log.isEmpty());
    }

    @Test
    void contextReadsInstanceTick() {
        List<Long> ticks = new ArrayList<>();
        FakeSelfPort self = new FakeSelfPort();
        MatchModeInstanceServices services =
                new MatchModeInstanceServices(self, new ComponentRegistry(), null);
        RuleBookComponent book = new RuleBookComponent.Spec(List.of(new Rule("tick-reader",
                ev -> ev instanceof Ping,
                ctx -> true,
                ctx -> ticks.add(ctx.tick())))).create("rules", services);

        self.advanceTick();
        self.advanceTick();
        book.onEvent(ping("x"));
        assertEquals(List.of(2L), ticks, "上下文的 tick 读自实例时钟");
    }

    @Test
    void triggerThrowIsAlsoIsolated() {
        List<String> log = new ArrayList<>();
        RuleBookComponent book = book(List.of(
                new Rule("throwing-trigger",
                        ev -> {
                            throw new IllegalStateException("trigger-boom");
                        },
                        ctx -> true,
                        ctx -> log.add("never")),
                new Rule("healthy",
                        ev -> ev instanceof Ping,
                        ctx -> true,
                        ctx -> log.add("healthy:fired"))));

        assertDoesNotThrow(() -> book.onEvent(ping("x")));
        assertEquals(List.of("healthy:fired"), log, "trigger 抛异常同样逐条隔离,不阻断");
    }
}
