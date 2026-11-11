package com.travelplus.agent.core;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【Agent架构升级的实现】提示词注册中心（Prompt Registry 模式，提示词版本化存储）。
 * <p>
 * 实现细节：
 * - 系统提示词从 Java 字符串常量迁移到 classpath:prompts/*.md 资源文件：
 *   提示词即资产（可评审、可 diff、可版本管理），调 prompt 不再需要改代码重编译；
 * - 启动期一次性加载，缺失直接抛异常阻断启动（fail fast，配置错误不留到运行期）；
 * - get() 全局唯一命名空间，后续多 Agent（攻略助手/客服/行程规划器）共用本注册中心。
 *
 * @author TravelPlus
 */
@Component
public class PromptRegistry {

    /** 旅游攻略助手提示词模板名 */
    public static final String TRAVEL_ASSISTANT = "travel-assistant";

    /** 【客服Agent升级的实现】智能客服 Agent 提示词模板名 */
    public static final String SUPPORT_AGENT = "support-agent";

    /** 追问建议生成器（回答完成后动态更新快捷 chips） */
    public static final String TRAVEL_FOLLOWUPS = "travel-followups";

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templates = new ConcurrentHashMap<>();

    public PromptRegistry(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** 启动期加载全部提示词模板 */
    @PostConstruct
    public void load() throws IOException {
        register("classpath:prompts/travel-assistant.md", TRAVEL_ASSISTANT);
        register("classpath:prompts/support-agent.md", SUPPORT_AGENT);
        register("classpath:prompts/travel-followups.md", TRAVEL_FOLLOWUPS);
    }

    /** 取模板（未注册直接抛异常） */
    public String get(String name) {
        String t = templates.get(name);
        if (t == null) {
            throw new IllegalArgumentException("未注册的提示词模板: " + name);
        }
        return t;
    }

    private void register(String location, String name) throws IOException {
        Resource res = resourceLoader.getResource(location);
        if (!res.exists()) {
            throw new IllegalStateException("提示词模板缺失: " + location);
        }
        templates.put(name, res.getContentAsString(StandardCharsets.UTF_8));
    }
}
