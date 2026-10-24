package com.hydrokarbs.engine.port;

/** 副本处置策略(03-会话与模式切换 §3.7 原样)。 */
public enum ResetStrategy {

    /** 打完不重建,靠清理组件把方块/实体还原(快,适合回合制连续开局)。 */
    REUSE_DIRTY,

    /** 切走时删除副本,下次获取从模板重新复制(慢但绝对干净,适合破坏性玩法)。 */
    TEMPLATE_RESTORE
}
