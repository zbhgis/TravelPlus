# 关注 Feed 实时分页 + 自通知验证记录

> 验证时间：2026-08-08（后端重启后实测，端口 8081）
> 验证身份：用户 1「小鱼同学」（phone 13686869696 / password 123456），token 由 /user/login 获取

## 一、后端重启
- 杀掉旧进程（PID 39808 Spring Boot 应用 + PID 29808 Maven launcher）后经 start-backend.bat 重启
- 新进程 PID 9156，Started TravelPlusApplication in 2.813s，Kafka 消费者订阅 seckill-ticket-order 正常
- GET /note/hot → HTTP 200 OK

## 二、关注 Feed 实时分页（GET /note/of/follow?current=N）
**关注列表（tb_follow WHERE user_id=1）：用户 2 / 1010 / 1013 / 1016（共 4 人，无 1011）**
- current=1 → total=8，返回 8 条，覆盖全部 4 位被关注作者：
  - 用户 2「可可今天不吃肉」：游记 4、5（visible=0）
  - 用户 1010「杭城小筑」：游记 9、12、21（visible=0）
  - 用户 1013「西湖听雨人」：游记 14、25（visible=0）
  - 用户 1016「杭漂摄影狮」：游记 17（visible=0）
- 所有 8 条均 visible=0（公开），作者覆盖 = 关注人数（4/4），无缺失、无去重丢失
- current=2 → total=8，返回 0 条（总条数 8 <= 每页 10，翻页正常无重复）
- 实现为实时 DB join（tb_follow -> tb_travel_note IN+visible=0），非 Redis feed 推送制

## 三、自通知（user 1 对自己游记 note 6「杭州西溪湿地...」操作）
| 动作 | 通知 id | type | from_user_id（from=自己） | targetId | 文案 |
|------|---------|------|---------------------------|----------|------|
| 自点赞（先取消再点，PUT /note/like/6） | 215 | like | 1（小鱼同学） | 6 | 小鱼同学赞了你的游记《杭州西溪湿地...》 |
| 自收藏（POST /favorite {note:6}） | 216 | favorite | 1（小鱼同学） | 6 | 小鱼同学收藏了你的游记《杭州西溪湿地...》 |
| 自评论（POST /note-comments {noteId:6,"自评论测试"}） | 217 | comment | 1（小鱼同学） | 6 | 小鱼同学评论了你的游记《杭州西溪湿地...》 |

- GET /notify/list?category=likes 含 like + favorite 两类自通知（from=自己 1）
- GET /notify/list?category=comments 含 comment 自通知（from=自己 1）
- 说明：NotifyServiceImpl.add 已不再排除「自己通知自己」，自赞/自收藏/自评论均落库

## 四、清理与最终状态
- 删除自评论（note-comments/32）→ 游记 6 comments 恢复 2；删除自收藏（favorite/note/6）→ 收藏关系清除
- 点赞在验证后回到原状态（isLike=true，liked=5，与验证前一致）
- 保留 3 条自通知（215/216/217）作为消息页演示数据（is_read=0）
- 后端保持运行中

## 结论
通过：关注 Feed 实时分页（4 作者全覆盖、visible=0 公开游记、翻页稳定）；自赞/自收藏/自评论均产生 from=自己的通知，且按 category 正确归类。