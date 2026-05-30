
## 05 文档已创建 (2026-08-05)
- 文件: docs/travelplus/05-移动端前端设计与规划.md (UTF-8)
- 章节清单:
  1. 目标与范围 (含降级策略: /note-comments 空壳、/user/logout 恒 fail)
  2. 技术选型与理由 (纯静态原生 + ES Modules + hash 路由, 5500 独立端口)
  3. 前后端对接契约 (通用约定 / 放行鉴权分类 / 28 条接口清单表 / 实体渲染字段 / 限流与未实现标注)
  4. 后端最小改动说明 (MvcConfig CORS + /imgs/** 静态映射, 已落盘核对)
  5. 页面与路由清单 (8 页 + tabbar 5 tab)
  6. 目录结构 (frontend/ 文件树, 标注已建/规划中)
  7. 鉴权与图片 URL 规则 (authorization / 401 / /imgs 拼接 / 分与 score 换算)
  8. 运行与验证步骤 (后端 8081 / 前端 5500 / 验证码途径 / 冒烟清单)
  9. 已知限制与后续计划 (评论 / logout / 上传目录 / 种子图片)
  10. 实施过程记录 (占位, 待实现完成后由 orchestrator 追加)

## 文档更新: 功能补全验证 (2026-08-06)
- 04-验证报告.md: 追加「八、功能补全与验证（Kafka消费链路/评论/登出/密码登录注册）」; 7.4 遗留事项三条(评论/登出/秒杀消费端)标记为已补全(见 8.2-8.4)
- 03-过程记录.md: 追加「功能补全记录（2026-08-06）」小节 (Kafka 3 Bean 启用+StringDeserializer bug 修正+AckMode MANUAL_IMMEDIATE、SeckillTicketConsumer @KafkaListener+JSONUtil、发送端 JSONUtil.toJsonStr、评论 2 端点、登出删 Redis token、密码登录+注册+BCrypt+旧用户 123456+travelplus.sql 同步)
- 05-移动端前端设计与规划.md: 第 9 节已知限制两行改为已实现(2026-08-06); 1.3 降级策略/3.3 接口表/3.5 未实现标注同步修正; 第 10 节追加前端更新说明(密码/验证码切换、注册页 #/register、评论区真实化、服务端登出)
- 全部 UTF-8 无 BOM (Edit 工具写入), 回读校验无乱码

## 文档更新: 社交功能验证 (2026-08-06)
- 04-验证报告.md: 追加「九、社交功能实现与验证（用户主页/关注粉丝/签到/删除游记）」章节 (9.1 环境 / 9.2 用户主页实时统计 GET /user/home/{id}: 用户2 关注0/粉丝2/获赞15/游记2、登录态 followed=true、未登录 followed=false / 9.3 关注粉丝列表 GET /follow/list/{id}?type=: 用户1 关注 3 人 total5、修复后 fans=2=total2 / 9.4 签到 BitMap 位序 BITFIELD u6=3/u7=6 LSB=今天、signCount 5/6 号→2 天、signList [5,6]、前端日历高亮 / 9.5 删除游记 DELETE /note/{id}: 非作者 fail/作者 success/删后「游记不存在」 / 9.6 修复记录: UserDTO isFollowed 默认 false + tb_follow 唯一约束 uk_user_follow + signCount 位序修正)
- 03-过程记录.md: 追加「社交功能记录（2026-08-06）」小节 (4 个新接口+契约: /user/home/{id}、/follow/list/{id}、/user/sign/list、DELETE /note/{id}; 前端用户主页/关注粉丝列表/签到日历/删除/头像跳转; tb_follow 去重+唯一约束 uk_user_follow+关注接口幂等; UserDTO NPE; signCount 位序; 验证摘要引用 04 九章)
- 05-移动端前端设计与规划.md: 第 9 节已知限制追加「社交功能已实现」行; 第 10 节追加社交功能前端更新说明
- 全部 UTF-8 无 BOM (Edit 工具写入), 回读校验无乱码
