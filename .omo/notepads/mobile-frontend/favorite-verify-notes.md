# 收藏功能 + 消息分类过滤 后端验证报告

- 日期：2026-08-07
- 环境：JDK 24 + Spring Boot 3.5.9，端口 8081，MySQL travelplus-mysql(3307) / Redis(6379)
- 后端：重启（杀旧进程 → start-backend.bat → /attraction-type/list 200）后验证，测试期间保持运行
- 账号：用户1 小鱼同学=13686869696（T1），用户2 可可今天不吃肉=13838411438（T2）

## A. 收藏功能

| # | 用例 | 结果 | 关键响应 |
|---|------|------|---------|
| 1 | 用户1 收藏游记5（用户2 的）`POST /favorite {targetType:note,targetId:5}` | ✅ | `{"success":true}` |
| 2 | 重复收藏（幂等） | ✅ | `{"success":true}`（唯一约束，不重复发通知） |
| 3 | 用户1 收藏景点17 `POST /favorite {targetType:attraction,targetId:17}` | ✅ | `{"success":true}` |
| 4 | 状态：`GET /favorite/status?type=note&id=5`(T1) | ✅ | `{"data":true}`；`type=note&id=4` → `{"data":false}` |
| 5 | 列表 `GET /favorite/list?type=note`(T1) | ✅ | 含 id=1,targetId=5,title=杭州灵隐寺…,image=/imgs/blogs/5/0.jpg,total=1；`type=attraction` → 含 targetId=17 千岛湖风景区 |
| 6 | 计数 `GET /favorite/count?type=note&id=5` | ✅ | `{"data":1}`（⚠️ 需登录，未带 token 返回 401，与控制器注释"公开"不符，建议确认 MvcConfig exclude 是否需放行） |
| 7 | 详情增强 `GET /note/5`(T1) | ✅ | `isFavorite:true, favoriteCount:1`；`GET /attraction/17`(T1) → `isFavorite:true, favoriteCount:1` |
| 8 | 用户2 通知 `GET /notify/list?current=1`(T2) | ✅ | 含 id=13 type=favorite，content=`小鱼同学收藏了你的游记《杭州灵隐寺…》`，fromUser=nickName 小鱼同学 |
| 9 | 取消收藏 `DELETE /favorite/note/5`(T1) | ✅ | `{"success":true}`；status → `{"data":false}`；重复 DELETE 幂等 → `{"success":true}` |

## B. 消息分类过滤

| # | 用例 | 结果 | 关键响应 |
|---|------|------|---------|
| 1 | 触发 like 通知：用户1 点赞游记5（先取消再点赞，`PUT /note/like/5`） | ✅ | 用户2 收到 id=14 type=like `小鱼同学赞了你的游记《杭州灵隐寺…》` |
| 2 | `category=likes`(T2) | ✅ | 3 条，全部 type=like/favorite（id=14 like、13 favorite、2 like） |
| 3 | `category=follows`(T2) | ✅ | `{"data":[],"total":0}`（无 follow 类，正确） |
| 4 | `category=comments`(T2) | ✅ | 3 条，全部 type=comment（id=8,6,5） |
| 5 | 非法 `category=xxx` | ✅ | `{"success":false,"errorMsg":"category 参数非法"}` |

## C. 清理

- ✅ `DELETE FROM tb_favorite WHERE user_id=1` 已执行（note5 收藏经接口取消、景点17 收藏已清），tb_favorite 残留 0 行
- ✅ 点赞计数一致性：unlike→relike 净变化 0，note5 liked=3（与点赞通知 id=14 一致）
- ✅ 收藏通知 id=13 保留 1 条作演示
- 后端保持运行，未做任何 git 操作

## 结论

收藏全链路（收藏/幂等/状态/列表/计数/详情增强/通知/取消）与消息三分类过滤（likes/follows/comments/非法参数）全部通过 ✅。唯一提示：`/favorite/count` 实际需登录（401），与控制器 javadoc 声明"公开"存在出入，需与前端确认游客收藏数展示是否依赖该接口。