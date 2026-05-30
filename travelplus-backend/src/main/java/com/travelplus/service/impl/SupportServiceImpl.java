package com.travelplus.service.impl;

import com.travelplus.config.AiProperties;
import com.travelplus.dto.Result;
import com.travelplus.mapper.ChatMessageMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.ISupportService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 【AI客服的实现】AI 智能客服服务实现：基于 langchain4j-open-ai 接入 DeepSeek 大模型。
 * <p>
 * 实现细节：
 * - 接入方式：DeepSeek 提供 OpenAI 兼容 API，故直接用 langchain4j 的 OpenAiChatModel
 *   （OpenAiChatModel.builder().baseUrl(...).apiKey(...).modelName(...).temperature(...).build()），
 *   baseUrl 取 application.yaml 的 https://api.deepseek.com，OpenAiChatModel 会在其后拼接 /chat/completions，
 *   DeepSeek 官方同时兼容有无 /v1 两种端点；模型名 deepseek-v4-flash。
 * - 系统提示设计：固定 SYSTEM_PROMPT 声明客服人设 + 平台功能覆盖（登录/注册、景点浏览检索、门票秒杀、
 *   游记发布点赞评论、关注粉丝、签到、浏览记录、消息通知），使客服能回答演示场景问题，且回答简洁友好用中文。
 * - 单轮无状态：chat() 每次组装 [SystemMessage, UserMessage] 后调用 chatModel.chat(messages)，
 *   不维护会话历史（可扩展为按 userId 在 Redis/内存缓存消息列表实现多轮上下文）。
 * - 失败降级：任何 AI 调用异常（网络不通、密钥无效、模型名错误、超时）统一 catch → log.error
 *   （只记 userId/message 与异常堆栈，不打印完整密钥）→ 异步任务内写一条兜底回复
 *   "（客服暂时无法回复，请稍后再试）"到 tb_chat_message，底层错误详情不暴露给前端，
 *   前端轮询 /chat/list?peerId=1021 必能得到一条回复而收敛（不会无限等待）。
 * - 【客服异步多线程的实现】异步化：chatModel.chat() 等待 DeepSeek 响应需 1-3s，同步调用会长时间
 *   占用 Tomcat 请求线程；现改为 chat() 同步落库用户消息后立即返回"已收到你的问题，思考中..."，
 *   AI 调用与回复落库提交到 supportTaskExecutor 线程池后台执行（见 config/AsyncConfig），
 *   请求线程不再阻塞等待大模型。接口返回语义变化：不再返回 AI 回复文本，回复经
 *   /chat/list?peerId=1021 拉取（前端轮询契约，并行开发中）。
 * - 【AI客服的实现】客服会话不受私信限制约束：chat() 走独立链路，服务类只注入 ChatMessageMapper/
 *   UserMapper（未注入 IChatService），不经过 ChatServiceImpl.send 的私信模式（dm_mode）三档限制
 *   与陌生人"回复前每日限条"逻辑——客服对任意已登录用户开放；落库 tb_chat_message 仅为让客服
 *   会话出现在消息栏「私信」列表，属于会话展示而非受限私信发送。
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SupportServiceImpl implements ISupportService {

    /**
     * 【AI客服的实现】系统提示词：固定客服人设与平台功能覆盖范围。
     * 覆盖平台能力（源自产品功能清单）：手机号/密码/验证码登录注册、景点浏览检索、门票秒杀（Lua+Kafka）、
     * 游记发布点赞评论、关注/粉丝、签到、浏览记录、消息通知。
     */
    private static final String SYSTEM_PROMPT =
            "你是 TravelPlus 旅游平台的智能客服，负责解答用户在使用平台过程中遇到的问题。"
            + "TravelPlus 平台提供以下功能：手机号、密码或验证码方式登录注册；景点浏览与搜索；"
            + "门票购买与限量秒杀（可能受库存、限购规则影响）；游记发布、点赞与评论；关注其他用户、查看粉丝；"
            + "每日签到获取积分；浏览记录；消息通知。"
            + "请基于这些功能回答用户关于登录、景点、游记、门票秒杀、订单、签到、关注等平台问题的咨询。"
            + "回答要简洁友好，使用中文；不确定的内容请如实说明，不要编造不存在的功能或数据。";

    private final AiProperties aiProperties;

    /**
     * 【客服会话的实现】客服虚拟用户 id：对应种子 tb_user 中 id=1021 的"TravelPlus客服"用户
     * （phone=10000000000、password='' 不可登录、icon 为系统默认头像，见 travelplus.sql）。
     * 固定常量便于会话落库时作为 from/to 使用，前端可从 /chat/list?peerId=1021 拉取客服历史。
     */
    private static final Long SUPPORT_USER_ID = 1021L;

    /**
     * 【客服异步多线程的实现】AI 调用失败/空回复时的兜底回复文案。
     * 异步任务内任何失败（网络不通/密钥无效/模型超时/空回复）都会把此文案写入 tb_chat_message，
     * 保证前端轮询 /chat/list?peerId=1021 必然等到一条"客服回复"而收敛（不会无限等待）。
     */
    private static final String FALLBACK_REPLY = "（客服暂时无法回复，请稍后再试）";

    /**
     * 【客服异步多线程的实现】客服 AI 调用专用线程池：提交异步生成 AI 回复的任务。
     * 线程池由 config/AsyncConfig 的 @Bean("supportTaskExecutor") 提供（core=2/max=4/queue=100/
     * CallerRunsPolicy），AI 调用属 IO 密集（等待 DeepSeek 网络响应 1-3s），2-4 线程已足够，
     * 避免占满 Tomcat 请求线程。通过 @Qualifier 按名称注入，避免与其他线程池 Bean 冲突。
     */
    private final ThreadPoolTaskExecutor supportExecutor;

    /**
     * 【客服会话的实现】私信消息 Mapper：直接注入 Mapper 而非 IChatService，避免
     * SupportServiceImpl → IChatService（实现类注入 IUserService 等）形成循环依赖；
     * 客服对话走私信消息表 tb_chat_message，落库后自动出现在消息栏「私信」会话列表。
     */
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 【客服会话的实现】用户 Mapper：校验当前用户与客服用户存在性（不存在仅跳过落库不影响回复），
     * 同样直接注入 Mapper 避免循环依赖（风格同 ChatServiceImpl 注入 FollowMapper）。
     */
    private final UserMapper userMapper;

    /**
     * 【AI客服的实现】langchain4j ChatModel（OpenAI 兼容协议），构建一次全局复用。
     * 使用构造器注入：bean 初始化时即从 AiProperties 读取配置构建模型，避免每次请求重复创建。
     */
    private final ChatModel chatModel;

    /**
     * 构造时基于配置构建 OpenAiChatModel，并注入会话落库所需的 Mapper 与客服异步线程池
     * @param aiProperties    travelplus.ai 配置（apiKey/baseUrl/model）
     * @param chatMessageMapper tb_chat_message 私信消息 Mapper（客服对话落库用）
     * @param userMapper       tb_user 用户 Mapper（用户/客服存在性校验用）
     * @param supportExecutor  客服 AI 调用专用线程池（见 config/AsyncConfig，异步生成 AI 回复用）
     */
    public SupportServiceImpl(AiProperties aiProperties,
                              ChatMessageMapper chatMessageMapper,
                              UserMapper userMapper,
                              @Qualifier("supportTaskExecutor") ThreadPoolTaskExecutor supportExecutor) {
        this.aiProperties = aiProperties;
        this.chatMessageMapper = chatMessageMapper;
        this.userMapper = userMapper;
        this.supportExecutor = supportExecutor;
        // DeepSeek 兼容 OpenAI API：baseUrl=api.deepseek.com，OpenAiChatModel 自动拼接 /chat/completions
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getModel())
                .temperature(0.7)
                .build();
    }

    /**
     * 【客服异步多线程的实现】单轮对话（异步）：请求线程立即返回，AI 回复在线程池后台生成并落库。
     * <p>
     * 实现细节：
     * - 【AI客服的实现】独立于私信限制：本方法不经 ChatServiceImpl.send，无 dm_mode 互关/陌生人
     *   限条校验，客服对任何已登录用户开放（不受私信限制约束，见类注释）。
     * - 为什么异步：chatModel.chat() 等待 DeepSeek 网络响应需 1-3s，同步调用会长时间占用 Tomcat
     *   请求线程；改造后请求线程只做"校验 + 用户消息落库 + 提交异步任务"，毫秒级返回，
     *   Tomcat 线程不被浪费，AI 调用交给 supportTaskExecutor 线程池隔离复用（参数见 config/AsyncConfig）。
     * - 用户消息先同步落库：persistUserMessage 在请求线程内执行，保证前端 /chat/list?peerId=1021
     *   立即能看到自己发的消息；AI 回复由异步任务后台写入（见 generateAndPersistReply）。
     * - 返回语义变化：接口不再返回 AI 回复文本，统一返回"已收到你的问题，思考中..."，
     *   AI 回复经 /chat/list?peerId=1021 轮询获取（前端并行开发中的契约）；失败时落兜底回复，
     *   保证前端轮询必能收敛。
     *
     * @param message 用户消息（非空，空消息 fail）
     * @param userId  当前登录用户 id（可为 null，此时不落库、仍提交异步生成）
     */
    @Override
    public Result chat(String message, Long userId) {
        // 1.参数校验：消息内容非空，空消息直接 fail（不落库、不提交 AI 任务）
        if (message == null || message.isBlank()) {
            return Result.fail("消息内容不能为空");
        }
        // 2.【客服异步多线程的实现】同步落库用户消息（from=当前用户→客服，is_read=1）：
        //    保证前端轮询历史立即可见；内部已容错（用户/客服不存在、写库异常仅记日志），不阻断返回。
        persistUserMessage(userId, message);
        // 3.【客服异步多线程的实现】提交异步任务：AI 调用（1-3s）+ 回复落库在 supportTaskExecutor
        //    后台线程执行，请求线程不等待大模型响应直接返回。线程池拒绝策略 CallerRunsPolicy
        //    保证消息不丢（队列满时由本请求线程兜底执行）。
        supportExecutor.execute(() -> generateAndPersistReply(userId, message));
        // 4.立即返回"思考中"占位：前端轮询 /chat/list?peerId=1021 获取 AI 回复（见类注释契约）
        return Result.ok("已收到你的问题，思考中...");
    }

    /**
     * 【客服异步多线程的实现】异步任务体：调用 DeepSeek 生成回复，成功写 AI 回复，失败写兜底回复。
     * <p>
     * 实现细节：
     * - 本方法在 supportTaskExecutor 线程池线程中执行（chat() 通过 supportExecutor.execute 提交），
     *   必须自行捕获所有异常——线程池内未捕获异常只落在任务内部，不像请求线程那样抛给上层，
     *   遗漏会导致 AI 回复既不落库也不留日志。
     * - 成功：chatModel.chat(messages) → persistAiReply 写 AI 回复（from=客服→用户，is_read=0）。
     * - 失败（网络不通/密钥无效/模型超时/空回复）：log.error 记录 userId/message（不打印密钥），
     *   再写兜底回复 FALLBACK_REPLY 到 tb_chat_message——保证前端轮询 /chat/list?peerId=1021
     *   必能等到一条"客服回复"而收敛（不会无限等待）。
     *
     * @param userId  当前登录用户 id（可为 null，此时仅记录日志不落库）
     * @param message 用户原始消息
     */
    private void generateAndPersistReply(Long userId, String message) {
        try {
            // 1.组装消息列表：系统提示固定，用户消息拼接原文（可附加当前用户 id，与改造前一致）
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            String userText = message;
            if (userId != null) {
                userText = message + "\n（当前用户 id：" + userId + "）";
            }
            messages.add(UserMessage.from(userText));

            // 2.调用 DeepSeek（langchain4j 1.x 使用 chat() 方法，老版本 generate() 已更名为 chat()）
            ChatResponse response = chatModel.chat(messages);
            String reply = response.aiMessage().text();
            if (reply == null || reply.isBlank()) {
                // 3.空回复兜底：写一条"稍后再试"回复，避免前端轮询空等（推理模型偶发 content 为空）
                log.warn("AI 客服返回空回复，写入兜底回复: userId={}", userId);
                persistAiReply(userId, FALLBACK_REPLY);
                return;
            }
            // 4.成功：AI 回复落库（from=客服→用户，is_read=0，计入私信未读数角标）
            persistAiReply(userId, reply);
        } catch (Exception e) {
            // 5.失败兜底：log.error（只记 userId/message 与堆栈，不打印密钥）+ 写兜底回复，
            //    保证前端轮询能结束；绝不向上抛异常（线程池任务需自行收敛）
            log.error("AI 客服异步调用失败: userId={}, message={}", userId, message, e);
            persistAiReply(userId, FALLBACK_REPLY);
        }
    }

    /**
     * 【客服异步多线程的实现】同步落库用户消息（from=当前用户→客服，is_read=1）。
     * <p>
     * 实现细节：
     * - 由原 persistConversation 拆分而来：用户消息必须在请求线程同步落库，
     *   前端 /chat/list?peerId=1021 立即能看到自己发的消息（异步部分只写 AI 回复）。
     * - 复用私信消息表：会话列表（ChatServiceImpl.sessions）按 (from_user_id=我 OR to_user_id=我)
     *   聚合出 peer 用户，客服=虚拟用户 1021，因此客服会话自然出现在消息栏私信会话列表。
     * - 容错与原逻辑一致：userId 为空、当前用户/客服用户不存在时仅 log.warn 跳过，
     *   写库失败绝不影响接口返回（客服对话是附加能力）。
     * - 落库内容：存用户原文 message（不含拼给模型的"（当前用户 id：x）"后缀），保持记录真实。
     *
     * @param userId  当前登录用户 id（可为 null，此时不落库）
     * @param message 用户原始消息
     */
    private void persistUserMessage(Long userId, String message) {
        // 1.未登录态容错：无当前用户 id 时无会话归属，直接跳过
        if (userId == null) {
            log.warn("客服对话未登录，跳过用户消息落库");
            return;
        }
        try {
            // 2.存在性校验：当前用户不存在（已注销）或客服用户缺失时跳过落库，不影响接口返回
            if (userMapper.selectById(userId) == null) {
                log.warn("客服对话落库跳过：当前用户不存在 userId={}", userId);
                return;
            }
            if (userMapper.selectById(SUPPORT_USER_ID) == null) {
                log.warn("客服对话落库跳过：客服虚拟用户缺失 supportUserId={}", SUPPORT_USER_ID);
                return;
            }
            // 3.用户消息：from=当前用户 → to=客服，is_read=1（自己发出即视为已读，不计未读）
            //    com.travelplus.entity.ChatMessage 与 langchain4j 的 ChatMessage 同名，此处用全限定名区分
            com.travelplus.entity.ChatMessage userMsg = new com.travelplus.entity.ChatMessage();
            userMsg.setFromUserId(userId);
            userMsg.setToUserId(SUPPORT_USER_ID);
            userMsg.setContent(message);
            userMsg.setIsRead(1);
            chatMessageMapper.insert(userMsg);
        } catch (Exception e) {
            // 4.落库异常兜底：客服对话是附加能力，写库失败只记日志，不得影响接口返回
            log.warn("客服用户消息落库失败: userId={}", userId, e);
        }
    }

    /**
     * 【客服异步多线程的实现】异步落库 AI 回复（from=客服→用户，is_read=0）。
     * <p>
     * 实现细节：
     * - 由原 persistConversation 拆分而来：AI 回复（正常回复或兜底回复）在线程池线程中写入，
     *   is_read=0（用户未读，计入私信未读数角标），前端轮询 /chat/list?peerId=1021 获取。
     * - 容错与原逻辑一致：userId 为空、当前用户/客服用户不存在时仅 log.warn 跳过，
     *   写库异常只记日志、不向外抛（异步线程内必须自收敛）。
     *
     * @param userId 当前登录用户 id（可为 null，此时不落库）
     * @param reply  AI 客服回复文本（正常回复或失败兜底回复 FALLBACK_REPLY）
     */
    private void persistAiReply(Long userId, String reply) {
        // 1.未登录态容错：无当前用户 id 时无会话归属，直接跳过
        if (userId == null) {
            log.warn("客服对话未登录，跳过 AI 回复落库");
            return;
        }
        try {
            // 2.存在性校验：当前用户不存在（已注销）或客服用户缺失时跳过落库（异步线程内静默跳过）
            if (userMapper.selectById(userId) == null) {
                log.warn("客服对话落库跳过：当前用户不存在 userId={}", userId);
                return;
            }
            if (userMapper.selectById(SUPPORT_USER_ID) == null) {
                log.warn("客服对话落库跳过：客服虚拟用户缺失 supportUserId={}", SUPPORT_USER_ID);
                return;
            }
            // 3.客服回复：from=客服 → to=当前用户，is_read=0（用户未读，计入私信未读数角标）
            com.travelplus.entity.ChatMessage supportMsg = new com.travelplus.entity.ChatMessage();
            supportMsg.setFromUserId(SUPPORT_USER_ID);
            supportMsg.setToUserId(userId);
            supportMsg.setContent(reply);
            supportMsg.setIsRead(0);
            chatMessageMapper.insert(supportMsg);
        } catch (Exception e) {
            // 4.落库异常兜底：客服对话是附加能力，写库失败只记日志，不在异步线程中抛异常
            log.warn("客服 AI 回复落库失败: userId={}", userId, e);
        }
    }
}
