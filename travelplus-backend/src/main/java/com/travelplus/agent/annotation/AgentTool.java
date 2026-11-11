package com.travelplus.agent.annotation;

import com.travelplus.agent.core.ToolPermission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【Agent架构升级的实现】Agent 工具注册注解（对标 Spring AI 的 @Tool 模式）。
 * <p>
 * 实现细节：
 * - 标注在工具 Bean 的公开方法上，由 {@link com.travelplus.agent.core.ToolRegistry} 在启动期扫描，
 *   根据注解元数据 + {@link ToolParam} 参数注解自动生成 OpenAI function calling 的 tools JSON，
 *   彻底替代旧版手写 TOOLS_JSON 字符串（新增工具零成本、schema 与实现强一致）。
 * - name 全局唯一（重复注册启动失败，fail fast）；description 直接喂给模型做工具选择。
 * - permission 控制调用门槛：PUBLIC 匿名可调（读类工具）；USER_REQUIRED 需登录（写类工具/个人信息），
 *   未登录时 ToolRegistry 直接向模型返回结构化错误，由模型转述引导用户登录。
 *
 * @author TravelPlus
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** 工具名（全局唯一，模型侧 function name） */
    String name();

    /** 工具描述（喂给模型，供其判断何时调用） */
    String description();

    /** 调用权限：PUBLIC=匿名可调；USER_REQUIRED=需登录（写操作/个人信息类） */
    ToolPermission permission() default ToolPermission.PUBLIC;
}
