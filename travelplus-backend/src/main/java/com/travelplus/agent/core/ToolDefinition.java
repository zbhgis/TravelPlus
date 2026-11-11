package com.travelplus.agent.core;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 【Agent架构升级的实现】单个 Agent 工具的运行时定义（schema 元数据 + 反射执行句柄）。
 * <p>
 * 实现细节：
 * - 启动期由 ToolRegistry 从 @AgentTool/@ToolParam 注解构建，一次构建运行期只读；
 * - params 描述模型侧 JSON schema（name/type/description/required）；
 * - method+bean 供反射调用，调用前后由 ToolRegistry 统一做参数校验、权限检查、
 *   UserHolder 注入与异常兜底——工具实现本身只写纯业务逻辑。
 *
 * @param name        工具名（全局唯一）
 * @param description 工具描述（喂给模型）
 * @param permission  权限等级
 * @param params      参数定义列表（生成 JSON schema）
 * @param method      反射方法句柄
 * @param bean        工具宿主 Bean
 * @author TravelPlus
 */
public record ToolDefinition(
        String name,
        String description,
        ToolPermission permission,
        List<ParamDefinition> params,
        Method method,
        Object bean) {

    /**
     * 单个参数定义：name=模型侧参数名；type=JSON 类型（string/integer/boolean/number）；
     * javaType=反射调用时的 Java 类型（用于实参强制转换）。
     */
    public record ParamDefinition(
            String name,
            String type,
            String description,
            boolean required,
            Class<?> javaType) {
    }
}
