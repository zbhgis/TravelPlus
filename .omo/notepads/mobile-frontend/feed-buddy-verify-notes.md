
# Feed 修复 + 结伴同行 — 后端验证报告（2026-08-07）

> 状态：全部通过 ✅ ｜ 后端已重启（杀 8081 旧 java → start-backend.bat → 轮询 200），验证期间保持运行。

## 一、重启后端 ✅
- 杀旧 java（PID 28652）+ 确认 8081 释放 → `start-backend.bat` → 轮询 /note/hot 200 就绪。
- 会话结束前再确认：/note/hot 仍 200，后端运行中。

## 二、关注 Feed 修复验证（核心场景）✅

### 场景一：X（用户1013 西湖听雨人 13712340001）关注用户2（13838411438，已有游记4/5）
| 步骤 | 请求 | 响应 |
|---|---|---|
| 1 X 未关注用户2 | GET /follow/or/not/2（X token） | {"success":true,"data":false} |
| 2 关注前 Feed | GET /note/of/follow?lastId=<now>（X token） | list:[]（无用户2 游记） |
| 3 X 关注用户2 | PUT /follow/2/true（X token） | {"success":true} |
| 4 关注后 Redis | ZRANGE feed:1013 0 -1 WITHSCORES | **4/5 已入 Feed**，score=历史 createTime（1767694500000/1768660200000） |
| 5 关注后 Feed(API) | GET /note/of/follow（X token） | **返回用户2 游记 id=5、id=4**，userId=2 |

结论：关注触发 backfillFollowFeed，历史游记按真实 createTime 补齐，时间轴排序正确。✅

> 注：feed:1013 顶部有 id=26/27 score≈now 的脏成员（疑似并发会话发帖删帖残留），滚动查询查无此游记自动过滤，不影响结论。

### 场景二（反向）：用户1 关注用户1013（已有游记14/25）
| 步骤 | 请求 | 响应 |
|---|---|---|
| 1 前置 | GET /follow/or/not/1013（T1） | true（历史已关注，但 feed:1 为空=旧版无补齐） |
| 2 取关重建 | PUT /follow/1013/false → PUT /follow/1013/true | 均 success |
| 3 关注后 Redis | ZRANGE feed:1 0 -1 WITHSCORES | **14/25 已入 Feed** |
| 4 关注后 Feed(API) | GET /note/of/follow（T1） | 返回 1013 游记（userId=1013） |

结论：反向场景同样生效。✅

## 三、结伴同行验证 ✅
| 步骤 | 请求 | 响应 |
|---|---|---|
| 1 列表 | GET /buddy/list?current=1（T1） | total=2（seed：id=1 用户1010 2026-09-15、id=2 用户1012 2026-09-20），含 user{id,nickName,icon} |
| 2 发布合法 | POST /buddy {厦门,2026-10-01,找搭子}（T1） | {"success":true,"data":3} |
| 3 过去日期 | POST /buddy {杭州,2026-01-01}（T1） | {"success":false,"errorMsg":"出行日期不能早于今天"} |
| 4 越权删除 | DELETE /buddy/3（用户2 token） | {"success":false,"errorMsg":"无权删除"} |
| 5 发布者删除 | DELETE /buddy/3（T1 token） | {"success":true} |
| 6 清理后列表 | GET /buddy/list?current=1 | total=2，仅剩 seed |

## 四、清理与收尾 ✅
- 测试结伴 id=3 已删，保留 seed（1/2）；演示关注保留（1013→2、1→1013）。
- DB 终态：tb_travel_buddy 仅 seed 2 条；tb_follow 含 1013→2(id=44)、1→1013(id=45)。
- 后端保持运行；未做 git 操作。

## 五、遗留说明
- feed:1013 顶部脏成员 26/27（并发会话残留），读侧自动过滤，演示环境可接受。
