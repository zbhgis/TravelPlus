package com.travelplus.agent.core;

/**
 * 【Agent架构升级的实现】工具调用权限等级。
 * <p>
 * 实现细节：
 * - PUBLIC：读类工具（搜景点/查游记），匿名可调——游客也能获得完整攻略体验；
 * - USER_REQUIRED：写类/个人信息类工具（查我的行程/加景点/建计划），未登录时
 *   ToolRegistry 返回 needLogin 结构化错误，模型据此引导用户登录后再操作。
 * 权限判定数据源是 ToolRegistry.invoke 显式传入的 UserDTO（异步线程显式传递登录态），
 * 而非隐式读 UserHolder——工具执行边界与线程上下文解耦，避免线程池串号。
 *
 * @author TravelPlus
 */
public enum ToolPermission {

    /** 匿名可调（读类工具） */
    PUBLIC,

    /** 需登录（写操作/个人信息类工具） */
    USER_REQUIRED
}
