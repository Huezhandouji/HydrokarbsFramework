package com.hydrokarbs.engine.service;

/**
 * 服务装配期失败专用异常(18 号修正案 §3;施工计划 1.6)。
 *
 * <p>两种触发形态,消息一律点名:
 * <ul>
 *   <li>缺必需依赖——点名声明方服务与缺失的依赖类型(双方);</li>
 *   <li>依赖成环——点名环上的全部服务。</li>
 * </ul>
 *
 * <p>由 {@link ServiceRegistry#seal()} 在依赖校验阶段抛出(★35);
 * 工厂/启动阶段的失败原样上抛(不走本异常——那是「启动失败」,不是「依赖声明失败」)。
 */
public final class ServiceDependencyException extends RuntimeException {

    public ServiceDependencyException(String message) {
        super(message);
    }

    public ServiceDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
