package com.travelplus.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelplus.agent.annotation.AgentTool;
import com.travelplus.agent.annotation.ToolParam;
import com.travelplus.dto.UserDTO;
import com.travelplus.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【Agent架构升级的实现】Agent 工具注册中心：注解扫描 → 自动 schema 生成 → 统一执行边界。
 * <p>
 * 实现细节（对标 Spring AI ToolCallingManager / OpenAI Agents SDK 的 Tool 框架）：
 * - 启动期扫描全部 {@link AgentToolProvider} Bean 上标注 {@link AgentTool} 的方法，
 *   结合 {@link ToolParam} 构建 {@link ToolDefinition}；重复工具名直接抛异常（fail fast）。
 * - toolsSchema()：把注册表序列化为 OpenAI function calling 的 tools JSON——新增工具只需
 *   写一个带注解的方法，schema 自动同步（替代旧版手写 TOOLS_JSON 字符串常量，杜绝漂移）。
 * - invoke()：统一执行边界——参数 JSON 解析 → required 校验（缺失回喂结构化错误，模型可自愈重试）
 *   → 权限检查（USER_REQUIRED 未登录返回 needLogin 错误）→ 实参强制转换 → UserHolder 注入
 *   （异步线程显式传登录态，用完即清）→ 反射调用 → 结果统一序列化为 JSON 字符串；
 *   任何异常都收敛为 {"error": "..."} 回喂模型，绝不向编排循环抛异常（故障隔离）。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class ToolRegistry {

    private final List<AgentToolProvider> providers;
    private final ObjectMapper objectMapper;

    /** 工具名 → 定义（LinkedHashMap 保持注册顺序，生成的 schema 顺序稳定） */
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /** 生成的 OpenAI tools JSON（启动期构建一次，运行期只读） */
    private volatile ArrayNode toolsSchemaCache;

    public ToolRegistry(List<AgentToolProvider> providers, ObjectMapper objectMapper) {
        this.providers = providers;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动期注册：扫描工具 Bean → 构建定义 → 生成 schema 缓存。
     * 任何工具定义不合法（缺 @ToolParam、重名）直接抛异常阻断启动。
     */
    @PostConstruct
    public void register() {
        for (AgentToolProvider provider : providers) {
            for (Method method : provider.getClass().getMethods()) {
                AgentTool ann = method.getAnnotation(AgentTool.class);
                if (ann == null) {
                    continue;
                }
                if (tools.containsKey(ann.name())) {
                    throw new IllegalStateException("Agent 工具名重复: " + ann.name());
                }
                List<ToolDefinition.ParamDefinition> params = new ArrayList<>();
                for (Parameter p : method.getParameters()) {
                    ToolParam tp = p.getAnnotation(ToolParam.class);
                    if (tp == null) {
                        throw new IllegalStateException("Agent 工具 " + ann.name()
                                + " 的参数缺少 @ToolParam 注解: " + p.getName());
                    }
                    params.add(new ToolDefinition.ParamDefinition(
                            tp.value(), jsonType(p.getType()), tp.description(), tp.required(), p.getType()));
                }
                method.setAccessible(true);
                tools.put(ann.name(), new ToolDefinition(
                        ann.name(), ann.description(), ann.permission(), List.copyOf(params), method, provider));
            }
        }
        toolsSchemaCache = buildSchema();
        log.info("【Agent架构升级】工具注册完成: {}", tools.keySet());
    }

    /** 模型侧 tools JSON（每次请求深拷贝，避免被请求侧修改污染缓存） */
    public ArrayNode toolsSchema() {
        return toolsSchemaCache.deepCopy();
    }

    /**
     * 【企业级客服Agent的实现】按白名单生成 tools JSON：客服 Agent 只能看到当前意图允许的工具。
     * 既减少无关工具对模型的干扰，也降低误调用写操作的风险。
     *
     * @param allowedTools 允许的工具名；null/空表示不限制
     */
    public ArrayNode toolsSchema(Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return toolsSchema();
        }
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolDefinition def : tools.values()) {
            if (allowedTools.contains(def.name())) {
                arr.add(buildToolNode(def));
            }
        }
        return arr;
    }

    /** 已注册工具数（供 /ai/metrics 观测） */
    public int toolCount() {
        return tools.size();
    }

    /**
     * 工具统一执行入口：校验 → 权限 → 反射调用 → JSON 序列化。
     * 任何失败都以 {"error": "..."} JSON 返回给模型（由模型决定转述或重试），
     * 不向编排循环抛异常——单工具故障不拖垮整次对话。
     *
     * @param toolName 模型请求的工具名
     * @param argsJson 模型给出的 arguments JSON 字符串
     * @param user     当前用户（可为 null=未登录；权限检查与 UserHolder 注入均基于它）
     * @return 工具结果的 JSON 字符串（role=tool 消息 content）
     */
    public String invoke(String toolName, String argsJson, UserDTO user) {
        return invoke(toolName, argsJson, user, null);
    }

    /**
     * 【企业级客服Agent的实现】带工具白名单的执行入口：模型即使幻觉出白名单外工具也拒绝执行。
     *
     * @param toolName     工具名
     * @param argsJson     参数 JSON
     * @param user         当前用户
     * @param allowedTools 工具白名单（null/空表示不限制）
     */
    public String invoke(String toolName, String argsJson, UserDTO user, Set<String> allowedTools) {
        if (allowedTools != null && !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            log.warn("【Agent工具】拒绝白名单外工具调用 tool={} allowed={}", toolName, allowedTools);
            return errorJson("工具不可用：" + toolName);
        }
        ToolDefinition def = tools.get(toolName);
        if (def == null) {
            return errorJson("未知工具：" + toolName);
        }
        JsonNode args;
        try {
            args = objectMapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            return errorJson("工具参数不是合法 JSON，请检查 arguments 格式");
        }
        /* required 参数前置校验：缺失直接回喂错误，模型可补参重试（自愈循环） */
        for (ToolDefinition.ParamDefinition p : def.params()) {
            if (!p.required()) {
                continue;
            }
            JsonNode v = args.path(p.name());
            if (v.isMissingNode() || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                return errorJson("缺少必填参数 " + p.name() + "（" + p.description() + "）");
            }
        }
        /* 权限边界：写类/个人信息类工具必须登录 */
        if (def.permission() == ToolPermission.USER_REQUIRED && user == null) {
            return errorJson("该操作需要登录后才能使用，请先在 App 内登录", true);
        }
        /* 实参强制转换（JSON 类型 → Java 形参类型） */
        Object[] argv = new Object[def.params().size()];
        for (int i = 0; i < def.params().size(); i++) {
            ToolDefinition.ParamDefinition p = def.params().get(i);
            argv[i] = coerce(args.path(p.name()), p.javaType());
        }
        try {
            /* 工具服务依赖登录态（UserHolder）：异步线程显式注入，finally 必清防线程池串号 */
            UserHolder.saveUser(user);
            Object result = def.method().invoke(def.bean(), argv);
            return result instanceof String s ? s : objectMapper.writeValueAsString(result);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() == null ? ite : ite.getCause();
            log.error("Agent 工具执行失败 tool={} err={}", toolName, cause.getMessage(), cause);
            return errorJson("工具执行失败，请稍后再试");
        } catch (Exception e) {
            log.error("Agent 工具调用异常 tool={}", toolName, e);
            return errorJson("工具执行失败，请稍后再试");
        } finally {
            UserHolder.removeUser();
        }
    }

    /** JSON 类型推断：Java 形参类型 → JSON schema type */
    private String jsonType(Class<?> type) {
        /* JsonNode 形参按数组下发：当前唯一用途是 create_trip 的 days 嵌套结构，
           若误标为 string，模型会传字符串导致工具实参类型不匹配 */
        if (JsonNode.class.isAssignableFrom(type)) {
            return "array";
        }
        if (type == String.class || type == char.class || type.isEnum()) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        return "string";
    }

    /** JsonNode → Java 实参（按目标形参类型强制转换；缺省时基本类型给零值、包装类型给 null） */
    private Object coerce(JsonNode v, Class<?> type) {
        if (v.isMissingNode() || v.isNull()) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == double.class) {
                return 0d;
            }
            return null;
        }
        /* JsonNode 形参保留原始 JSON 结构（asText 会把数组/对象压成空串） */
        if (JsonNode.class.isAssignableFrom(type)) {
            return v;
        }
        if (type == String.class) {
            return v.asText();
        }
        if (type == int.class || type == Integer.class) {
            return v.asInt();
        }
        if (type == long.class || type == Long.class) {
            return v.asLong();
        }
        if (type == boolean.class || type == Boolean.class) {
            return v.asBoolean();
        }
        if (type == double.class || type == Double.class) {
            return v.asDouble();
        }
        if (type == float.class || type == Float.class) {
            return (float) v.asDouble();
        }
        return v.asText();
    }

    /** 构建模型侧 tools schema（OpenAI function calling 格式） */
    private ArrayNode buildSchema() {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolDefinition def : tools.values()) {
            arr.add(buildToolNode(def));
        }
        return arr;
    }

    /** 单个工具的 OpenAI function calling schema 节点 */
    private ObjectNode buildToolNode(ToolDefinition def) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", def.name());
        fn.put("description", def.description());
        ObjectNode parameters = fn.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode props = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");
        for (ToolDefinition.ParamDefinition p : def.params()) {
            ObjectNode pn = props.putObject(p.name());
            pn.put("type", p.type());
            if (!p.description().isEmpty()) {
                pn.put("description", p.description());
            }
            if (p.required()) {
                required.add(p.name());
            }
        }
        return tool;
    }

    private String errorJson(String msg) {
        return errorJson(msg, false);
    }

    private String errorJson(String msg, boolean needLogin) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("error", msg);
        if (needLogin) {
            n.put("needLogin", true);
        }
        return n.toString();
    }
}
