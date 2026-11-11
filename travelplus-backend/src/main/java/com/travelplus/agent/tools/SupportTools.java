package com.travelplus.agent.tools;

import com.travelplus.agent.annotation.AgentTool;
import com.travelplus.agent.annotation.ToolParam;
import com.travelplus.agent.core.AgentToolProvider;
import com.travelplus.agent.core.ToolPermission;
import com.travelplus.agent.support.SupportHandoffService;
import com.travelplus.agent.support.SupportKnowledgeBase;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.SeckillTicket;
import com.travelplus.entity.SupportHandoff;
import com.travelplus.entity.Ticket;
import com.travelplus.service.ISeckillTicketService;
import com.travelplus.service.ITicketOrderService;
import com.travelplus.service.ITicketService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【客服Agent升级的实现】客服类 Agent 工具集：把「客服话术」升级为「可查真实数据」。
 * <p>
 * 实现细节：
 * - 个人信息/订单类工具全部 USER_REQUIRED：身份由 ToolRegistry 注入 UserHolder，工具不接收 userId 参数，
 *   模型无法通过参数越权查询他人订单；
 * - 查询逻辑全部复用现有 Service，口径与 App 页面一致（订单状态文案、钱包积分、签到日期数组）；
 * - 秒杀活动为公开信息（PUBLIC），返回进行中且库存 > 0 的场次，避免客服让用户去抢已结束的活动；
 * - 知识库检索工具让模型在首轮注入的 Top3 之外按需二次检索，回答规则问题时可追溯来源。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SupportTools implements AgentToolProvider {

    /** 秒杀活动单次返回上限 */
    private static final int MAX_SECKILL_DEALS = 8;

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private ISeckillTicketService seckillTicketService;

    @Resource
    private ITicketService ticketService;

    @Resource
    private IUserService userService;

    @Resource
    private SupportKnowledgeBase knowledgeBase;

    @Resource
    private SupportHandoffService handoffService;

    /** 查当前用户订单列表（支持状态过滤） */
    @AgentTool(name = "get_my_orders",
            description = "查询当前登录用户的门票订单列表（订单号/门票/金额/状态/下单时间），"
                    + "用户问「我的订单」「买过的票」「订单到哪了」时必须先调用",
            permission = ToolPermission.USER_REQUIRED)
    public Object getMyOrders(
            @ToolParam(value = "status", description = "订单状态过滤：1未支付 2已支付 3已核销 4已取消 5退款中 6已退款；不传=全部") Integer status) {
        Result r = ticketOrderService.queryOrderList(1, status);
        if (r.getSuccess() == null || !r.getSuccess()) {
            return Map.of("error", r.getErrorMsg() == null ? "订单查询失败" : r.getErrorMsg());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", r.getTotal());
        out.put("orders", r.getData());
        return out;
    }

    /** 查单笔订单详情（归属校验由 Service 完成） */
    @AgentTool(name = "get_order_detail",
            description = "查询当前用户某笔门票订单的详情（门票名称、金额、状态、支付/核销时间、使用规则），"
                    + "用户提供订单号或询问某笔订单时必须先调用",
            permission = ToolPermission.USER_REQUIRED)
    public Object getOrderDetail(
            @ToolParam(value = "orderId", description = "订单号（来自 get_my_orders 返回的 id）", required = true) long orderId) {
        Result r = ticketOrderService.queryOrderDetail(orderId);
        if (r.getSuccess() == null || !r.getSuccess()) {
            return Map.of("error", r.getErrorMsg() == null ? "订单不存在" : r.getErrorMsg());
        }
        return r.getData();
    }

    /** 当前进行中的秒杀场次（公开信息） */
    @AgentTool(name = "get_seckill_deals",
            description = "查询当前正在进行且还有库存的秒杀门票场次（门票名/价格/剩余库存/结束时间），"
                    + "用户问「现在有什么秒杀」「还有票吗」时调用",
            permission = ToolPermission.PUBLIC)
    public Object getSeckillDeals() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillTicket> active = new ArrayList<>();
        for (SeckillTicket s : seckillTicketService.list()) {
            if (s.getStock() != null && s.getStock() > 0
                    && s.getBeginTime() != null && s.getEndTime() != null
                    && !now.isBefore(s.getBeginTime()) && !now.isAfter(s.getEndTime())) {
                active.add(s);
            }
        }
        if (active.isEmpty()) {
            return Map.of("message", "当前没有进行中的秒杀活动");
        }
        active.sort(Comparator.comparing(SeckillTicket::getEndTime));
        if (active.size() > MAX_SECKILL_DEALS) {
            active = active.subList(0, MAX_SECKILL_DEALS);
        }
        List<Long> ticketIds = new ArrayList<>();
        for (SeckillTicket s : active) {
            ticketIds.add(s.getTicketId());
        }
        Map<Long, Ticket> ticketMap = new HashMap<>();
        for (Ticket t : ticketService.listByIds(ticketIds)) {
            ticketMap.put(t.getId(), t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SeckillTicket s : active) {
            Ticket t = ticketMap.get(s.getTicketId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ticketId", s.getTicketId());
            item.put("ticketName", t == null ? "未知门票" : t.getTitle());
            item.put("price", t == null || t.getPayValue() == null ? null : "¥" + (t.getPayValue() / 100.0));
            item.put("stock", s.getStock());
            item.put("endTime", s.getEndTime());
            out.add(item);
        }
        return out;
    }

    /** 当前用户资产与签到状态 */
    @AgentTool(name = "get_my_account",
            description = "查询当前登录用户的余额、积分、可用秒杀券数量、本月签到日期与连续签到天数，"
                    + "用户问「我的积分」「签到」「余额」时调用",
            permission = ToolPermission.USER_REQUIRED)
    public Object getMyAccount() {
        Map<String, Object> out = new LinkedHashMap<>();
        Result wallet = userService.queryWallet();
        out.put("wallet", wallet.getSuccess() != null && wallet.getSuccess() ? wallet.getData() : null);
        Result streak = userService.signCount();
        out.put("signStreakDays", streak.getSuccess() != null && streak.getSuccess() ? streak.getData() : 0);
        List<Integer> signedDays = new ArrayList<>();
        Result days = userService.signList(null);
        if (days.getData() instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    signedDays.add(n.intValue());
                }
            }
        }
        out.put("signedDaysThisMonth", signedDays);
        out.put("todaySigned", signedDays.contains(LocalDate.now().getDayOfMonth()));
        return out;
    }

    /** 客服知识库显式检索 */
    @AgentTool(name = "search_support_kb",
            description = "检索 TravelPlus 客服知识库（登录注册、秒杀规则、订单支付、退款售后、签到积分、隐私设置等），"
                    + "返回知识标题与正文片段；回答平台规则问题时用于补充依据",
            permission = ToolPermission.PUBLIC)
    public Object searchSupportKb(
            @ToolParam(value = "keyword", description = "检索关键词，如 退款规则、秒杀、签到", required = true) String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return knowledgeBase.search(keyword.trim(), 3);
    }

    /**
     * 【企业级客服Agent的实现】转人工工具：模型判断无法解决或用户诉求涉及投诉/安全时调用。
     * 工单创建后会话立即进入 WAITING，AI 后续静默，由人工工作台接管。
     */
    @AgentTool(name = "request_human_handoff",
            description = "创建人工客服工单并转接人工：当用户明确要求人工、投诉/举报、账号安全、"
                    + "资金争议，或你无法解决时调用；调用后请告知用户工单号并请其等待人工客服",
            permission = ToolPermission.USER_REQUIRED)
    public Object requestHumanHandoff(
            @ToolParam(value = "reason", description = "转人工原因摘要", required = true) String reason) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Map.of("error", "该操作需要登录后才能使用，请先在 App 内登录", "needLogin", true);
        }
        SupportHandoff ticket = handoffService.request(user.getId(), reason, "agent");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", ticket.getId());
        out.put("status", "WAITING");
        out.put("message", "已创建人工工单，人工客服会尽快接入；请告知用户工单号并请其等待");
        return out;
    }
}
