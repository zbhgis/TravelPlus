package com.travelplus.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【Agent架构升级的实现】Agent 工具参数注解：声明参数名/描述/是否必填。
 * <p>
 * 实现细节：
 * - 参数名用 value 显式声明而非依赖反射读形参名——不依赖 -parameters 编译开关，
 *   任何构建环境下 schema 都确定无误（防御性设计）。
 * - required=true 的参数由 ToolRegistry 在调用前校验，缺失时直接返回结构化错误
 *   JSON 回喂模型（自愈循环：模型可据此补参重试，而不是把异常抛给用户）。
 *
 * @author TravelPlus
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** 参数名（显式声明，生成 JSON schema 的 properties key） */
    String value();

    /** 参数描述（喂给模型） */
    String description() default "";

    /** 是否必填（调用前校验，缺失则向模型返回参数错误） */
    boolean required() default false;
}
