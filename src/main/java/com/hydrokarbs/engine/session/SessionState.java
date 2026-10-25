package com.hydrokarbs.engine.session;

/**
 * 会话状态机的四态(03-会话与模式切换 §3.1;2.1 施工步骤 7)。
 *
 * <p>封闭枚举:新增值须过设计评审——它会进事件载荷与日志,是事实上的对外词汇。
 *
 * <p>合法迁移矩阵(§3.1 状态机图、§3.2 阶段 B;迁移写点收敛在 Session 的包级方法,
 * 2.3 的 SessionServiceImpl 是唯一调用者):
 *
 * <table border="1">
 *   <caption>合法迁移</caption>
 *   <tr><th>迁移</th><th>触发</th><th>出处</th></tr>
 *   <tr><td>RUNNING→ENDING</td><td>组件经 end() 端口请求(置位,不立即切)</td><td>§3.1 状态机图</td></tr>
 *   <tr><td>RUNNING→SWITCHING</td><td>外部 switchMode 阶段 A 就绪后挂起</td><td>§3.1、§3.2</td></tr>
 *   <tr><td>ENDING→SWITCHING</td><td>节拍器在结束后的下一 tick 驱动切换</td><td>§3.1、§3.2</td></tr>
 *   <tr><td>SWITCHING→RUNNING</td><td>B 段 attach 成功(新模式);失败走三级回退</td><td>§3.2</td></tr>
 *   <tr><td>RUNNING/ENDING/SWITCHING→DISSOLVING</td><td>空场超时(arena)/管理命令/onDisable</td><td>§3.1</td></tr>
 * </table>
 *
 * <p>DISSOLVING 是终态,无出边。ENDING 与 SWITCHING 是显式中间态
 * (结束判定不立即清场,清场由节拍器在下一 tick 驱动——切换永远不发生在事件派发栈内)。
 */
public enum SessionState {

    /** 常态:当前实例 tick,成员自由进出。 */
    RUNNING,
    /** 结束已请求、未切:等节拍器下一 tick 驱动(显式中间态)。 */
    ENDING,
    /** 切换执行中:旧实例 close → 名册原地位移 → 新实例 attach(主线程单 tick 原子)。 */
    SWITCHING,
    /** 解散中(终态):清场与摘除完成后会话终结。 */
    DISSOLVING;

    /** 是否可从本态迁往目标态(非法迁移由守卫拒绝;自迁移一律非法)。 */
    public boolean canTransitionTo(SessionState target) {
        return switch (this) {
            case RUNNING -> target == ENDING || target == SWITCHING || target == DISSOLVING;
            case ENDING -> target == SWITCHING || target == DISSOLVING;
            case SWITCHING -> target == RUNNING || target == DISSOLVING;
            case DISSOLVING -> false;
        };
    }
}
