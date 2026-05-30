package com.travelplus.mapper;

import com.travelplus.entity.SeckillTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 【秒杀门票数据访问的实现】秒杀门票表 tb_seckill_ticket（与门票一对一同主键）的 MyBatis-Plus Mapper，
 * 继承 BaseMapper 获得通用 CRUD 能力；库存初始装载与查询在秒杀链路 Service 层使用。
 * </p>
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
public interface SeckillTicketMapper extends BaseMapper<SeckillTicket> {

    /**
     * 【门票信息的实现】批量查询"有秒杀票"的景点 id（列表接口 hasSeckill 判定用）
     * <p>
     * 实现细节：秒杀券表 tb_seckill_ticket 与门票表 tb_ticket 一对一同主键（s.ticket_id=t.id），
     * 通过 JOIN 把"哪些门票是秒杀券"过滤出来，再 DISTINCT 到门票归属景点 attraction_id；
     * 一次批量 IN 查询避免逐景点 N+1（与 attachListInfo 列表门票标记配合使用）。
     * @param ids 待判定的景点 id 集合
     * @return 有秒杀票的景点 id 列表
     */
    @Select("<script>SELECT DISTINCT t.attraction_id FROM tb_seckill_ticket s " +
            "JOIN tb_ticket t ON s.ticket_id = t.id " +
            "WHERE t.attraction_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Long> selectAttractionIdsWithSeckill(@Param("ids") List<Long> ids);
}
