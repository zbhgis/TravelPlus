package com.travelplus.agent.core;

/**
 * 【Agent架构升级的实现】Agent 工具提供者标记接口。
 * <p>
 * 实现细节：
 * - 空标记接口：工具 Bean（TripTools/AttractionTools）实现它，ToolRegistry 注入
 *   List&lt;AgentToolProvider&gt; 即可发现全部工具宿主，再扫描其方法上的 @AgentTool 注解注册。
 * - 相比 ApplicationContext 全量 Bean 扫描，显式标记可控可读（工具边界清晰），
 *   也是企业框架常见的 SPI 注册风格。
 *
 * @author TravelPlus
 */
public interface AgentToolProvider {
}
