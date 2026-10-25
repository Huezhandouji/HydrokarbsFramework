package com.hydrokarbs.bridge.config;

/**
 * 配置装载异常(15-适配 §15.2 解析实现要点;施工计划 2.2)。
 *
 * <p>YAML → 模型的统一失败语义:消息必须带<b>文件名</b>(与行号——底层解析器
 * 的定位信息原样并入消息)。属「单文件局部失败」:装载编排层捕获后跳过该文件
 * 并记 SEVERE,不中断其余文件装载(§15.2 铁律 3)。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
