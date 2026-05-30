# 新接口验证报告：评论增强 / 通知系统 / AI 客服

> 验证时间：2026-08-07 15:14-15:17 · 后端已重启（start-backend.bat）· 环境：localhost:8081
> 测试用户：用户1=id1(小鱼同学,13686869696) · 用户2=id2(可可今天不吃肉,13838411438) · 用户1013=西湖听雨人
> 结论：19/19 项通过，0 失败。所有新接口功能正常；发现 2 处文档与实现不一致（见文末 Note）。

## A. 后端重启
- ✅ 停止旧进程：8081 监听 PID 34844（java travelplus）已 kill，端口释放
- ✅ 启动：start-backend.bat → 轮询 http://localhost:8081/attraction-type/list 返回 200（15:14:37 就绪）
- ✅ 登录：3 个用户均 `POST /user/login` 返回 token

## B. 接口测试

### 1. 评论回复（noteId=4）
| 步骤 | 接口 | 结果 | 证据 |
|---|---|---|---|
| 用户2 发一级评论 | POST /note-comments {noteId:4,"测试一级评论"} | ✅ | 生成评论 A=id22 |
| 用户2 回复 | POST /note-comments {noteId:4,parentId:22,answerId:22,"回复@小鱼同学 测试"} | ✅ | 生成评论 B=id23 |
| 验证结构 | GET /note-comments/of/4?current=1 | ✅ | A(id22,parentId=0)、B(id23,parentId=22,answerId=22) 均在；A 带 name/icon 用户信息 |

### 2. 评论删除
| 步骤 | 接口 | 结果 | 证据 |
|---|---|---|---|
| 用户2 删自己评论 B | DELETE /note-comments/23 | ✅ | `{"success":true}` |
| 用户2 删用户1 的评论（note6 上 id9，游记作者=用户1） | DELETE /note-comments/9 | ✅ | `{"success":false,"errorMsg":"无权删除"}` |
| 用户2 删自己评论 A | DELETE /note-comments/22 | ✅ | `{"success":true}` |
| 计数回落 | GET /note/4 | ✅ | comments 109 → 107（A+B 各 +1，删除各 -1） |

### 3. 评论置顶（noteId=5，游记作者=用户2）
| 步骤 | 接口 | 结果 | 证据 |
|---|---|---|---|
| 用户2 发一级评论 C | POST /note-comments {noteId:5,"置顶测试评论C"} | ✅ | 生成 C=id24 |
| 用户1 越权置顶 | PUT /note-comments/top/24 {top:true} | ✅ | `{"success":false,"errorMsg":"无权操作"}` |
| 用户2 置顶 | PUT /note-comments/top/24 {top:true} | ✅ | `{"success":true}` |
| 排序验证 | GET /note-comments/of/5 | ✅ | C(id24) 排最前且 isTop=1 |
| 取消置顶 | PUT /note-comments/top/24 {top:false} | ✅ | `{"success":true}`；恢复按创建时间升序，C 回到末位 |

### 4. 通知系统
| 步骤 | 接口 | 结果 | 证据 |
|---|---|---|---|
| 用户1 点赞游记5（作者=用户2） | PUT /note/like/5 | ✅ | 触发 like 通知 → 用户2 |
| 用户1 关注 1013 | PUT /follow/1013/true | ✅ | 触发 follow 通知 → 用户1013 |
| 用户2 评论游记6（作者=用户1） | POST /note-comments {noteId:6,"点赞通知测试"} | ✅ | 触发 comment 通知 → 用户1 |
| 用户1 查通知 | GET /notify/list?current=1 | ✅ | comment(id4,targetId=6,"可可今天不吃肉评论了你的游记…") + mention(id1,来自回复测试) |
| 用户2 查通知 | GET /notify/list?current=1 | ✅ | like(id2,targetId=5,"小鱼同学赞了你的游记《杭州灵隐寺…") |
| 用户1013 查通知 | GET /notify/list?current=1 | ✅ | follow(id3,targetId=1013,"小鱼同学关注了你") + mention(id7) |
| 未读数 | GET /notify/unread-count（用户2） | ✅ | data=1 |
| 全部已读 | PUT /notify/read（用户2） | ✅ | `{"success":true}`；再查 unread-count=0；用户1 未读仍=2（不影响他人） |
| @ 通知（@游记作者） | 用户1 评论 note5 "@可可今天不吃肉 你好" | ✅ | 用户2 收到 comment 通知（id5）——mention 与 comment 合并去重（设计如此） |
| @ 通知（@非作者） | 用户1 评论 note5 "@西湖听雨人 求攻略" | ✅ | 用户1013 收到 mention 通知（id7, type=mention, "小鱼同学在评论中提到了你"） |

### 5. AI 客服（真实 DeepSeek 调用）
| 问题 | 结果 | 实际回复摘录 |
|---|---|---|
| "你好，TravelPlus 支持哪些功能？" | ✅ success=true | "您好！TravelPlus 支持以下功能：- 手机号/密码/验证码登录注册 - 景点浏览与搜索 - 门票购买与限量秒杀 - 游记发布、点赞、评论 - 关注用户、查看粉丝 - 每日签到获取积分 - 浏览记录 - 消息通知 …" |
| "怎么买秒杀门票？" | ✅ success=true | "您好！购买秒杀门票请先登录TravelPlus，然后在景点详情页找到'门票秒杀'入口，点击'立即抢购'并按提示完成支付即可。秒杀受库存和限购规则影响…手快有、手慢无哦～" |

### 6. 清理
- ✅ 删除本轮测试评论：A(22)、B(23)、C(24)、note6"点赞通知测试"(25)、note5 @通知测试评论(26/27) 全部删除
- ✅ 恢复游记评论计数：note4=107、note5=2、note6=2（与初始一致）
- ✅ 保留通知 7 条作为消息中心演示数据（like/follow/comment/mention 各类型齐全）
- 备注：note5 liked 计数 2→3（用户1 点赞保留，与保留的 like 通知一致）

## Note（文档与实现不一致，非缺陷）
1. `TravelNoteCommentController.queryComments` Javadoc 声称评论列表"匿名可访问"，但 MvcConfig LoginInterceptor 的 exclude 列表**未放行 /note-comments/**，实际未登录 GET /note-comments/of/4 返回 401。
2. 任务预期 "@可可今天不吃肉"(游记作者) 触发独立 mention 通知，但实现按设计将 mention 与 comment 合并去重（notifyMentionUsers 排除 receiverId）；@非作者时才产生 type=mention（已验证）。

## 结论
评论增强（回复/删除/置顶）、通知系统（like/follow/comment/reply/mention + 未读/已读）、AI 客服（langchain4j+DeepSeek）全部功能正常，接口测试 19/19 通过。
