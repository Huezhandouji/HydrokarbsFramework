package com.hydrokarbs.engine.session;

import java.util.Collection;

/**
 * 会话服务:会话的创建/查询/切换/解散面(03-会话与模式切换 §3.2)。
 *
 * <p><b>最小壳接口</b>(施工计划 1.3):本阶段只有 {@link #all()}——
 * 全局节拍器 {@code tickAll} 遍历全部会话所需(12-代码骨架 §12.2)。
 *
 * <p>完整 §3.2 方法面({@code create}/{@code sessionOf}/{@code session}/
 * {@code switchMode}/{@code join}/{@code leave}/{@code dissolve} 与 {@code SwitchResult}
 * 拒绝码)由阶段 2(2.3)按设计案立出,本壳届时整体替换。
 * ★ 本阶段不预写 {@code SwitchResult}/{@code JoinResult}——那是 2.3 的冻结公共契约,
 * 提前写等于替阶段 2 做设计。
 */
public interface SessionService {

    /** 全部存活会话(只读遍历;节拍器每 tick 遍历它)。 */
    Collection<Session> all();
}
