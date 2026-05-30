package com.travelplus.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 【统一响应体的实现】全站接口统一返回结构 {success, errorMsg, data, total}。
 * success 标记成功与否；失败时 errorMsg 携带错误描述；data 为业务数据；
 * total 仅分页列表（ok(List, Long)）时携带总数。静态工厂方法 ok()/fail() 供各层快捷构造，
 * 序列化时空字段不输出（见后端对接契约）。
 */
/**
 * 【统一返回结果封装】所有 Controller 接口的统一响应体
 * <p>
 * 实现细节：
 * - 字段语义：success=业务是否成功；errorMsg=失败原因（成功时为 null）；data=业务数据；
 *   total=分页总数（非分页接口为 null）。
 * - 静态工厂：ok()/ok(data)/ok(list, total)/fail(errorMsg) 四种形态覆盖成功无数据、
 *   成功带数据、分页成功、失败四种场景；JSON 序列化时 null 字段不输出（non_null 配置）。
 * - 前端判断逻辑：success=true 视为成功，读取 data；success=false 展示 errorMsg。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;

    /** 成功（无数据） */
    public static Result ok(){
        return new Result(true, null, null, null);
    }
    /** 成功（携带单个业务数据） */
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    /** 成功（分页列表 + 总条数） */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    /** 失败（携带错误描述） */
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
