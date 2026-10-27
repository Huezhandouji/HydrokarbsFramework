package com.hydrokarbs.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.hydrokarbs.engine.component.SessionComponent;

/**
 * 会话配方:单次贡献的建会话声明(★v1.1-D14 通道二;07-公共API §7.1 第二代码块;
 * 07 §7.3「受限开放」)。本 record 是 {@link MiniGameApi#createSession} 的唯一参数形态
 * (★v1.4:两参重载已删除;便捷工厂见 {@link #of})。
 *
 * <p><b>合并序(D14;装配点唯一不变)</b>:已收录 provider 贡献(注册序,D14 通道一)
 * → 本 record 的 {@code extraComponents}——<b>贡献只发生在会话创建时,无运行期增删</b>;
 * 模式仍无权贡献(v4-D4 不变)。合并后统一过装配闸:组件 id 查重(同命名空间)/
 * 缺必需依赖 → {@code createSession} <b>响亮抛</b>(装配期失败代价低,与既有会话
 * 创建语义一致,§7.3)。
 *
 * <p><b>字段口径</b>:
 * <ul>
 *   <li>{@code initialModeId}——首切换目标(必填,未注册响亮抛,引擎 require
 *       既有口径,§12.1);{@code initialMapId}——钉图(空白 = 未指定,空池自动挑图);
 *       非空白时置会话地图池<b>首位</b>(首切换自动挑图按池序,§3.2-A3);</li>
 *   <li>{@code mapPool}——会话地图池(整场约束,与钉图去重合并);</li>
 *   <li>{@code extraComponents}——单次贡献的会话组件描述符({@code Specification} 在
 *       07 §7.2 白名单;组件 id 由供给面类型名推导,与引擎装配路径同源)。</li>
 * </ul>
 */
public record SessionRecipe(String initialModeId, Optional<String> initialMapId,
                            List<String> mapPool,
                            List<SessionComponent.Specification<?>> extraComponents) {

    public SessionRecipe {
        Objects.requireNonNull(initialModeId, "initialModeId");
        Objects.requireNonNull(initialMapId, "initialMapId");
        Objects.requireNonNull(mapPool, "mapPool");
        Objects.requireNonNull(extraComponents, "extraComponents");
        mapPool = List.copyOf(mapPool);
        extraComponents = List.copyOf(extraComponents);
    }

    /** 便捷工厂(07 §7.1 骨架):钉图 + 空池 + 零单次贡献;{@code mapId} 为 null 视同未钉图。 */
    public static SessionRecipe of(String modeId, String mapId) {
        return new SessionRecipe(modeId, Optional.ofNullable(mapId), List.of(), List.of());
    }
}
