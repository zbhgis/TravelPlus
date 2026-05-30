# 列表 attach 动态评分/打卡/门票 — 验证记录
# 时间：2026-08-07；后端已重启；未 git 操作；后端保持运行（orchestrator E2E 用）

## 1. 后端重启 OK
- 杀 8081 旧监听 PID 40104 → 端口释放
- start-backend.bat 启动 → 日志 Started TravelPlusApplication in 3.125 seconds，PID 28528 监听 8081
- 探活：根路径 / 非 200（无映射）；真实接口 GET /attraction/of/type 返回 200 OK

## 2. 登录 OK
- POST /user/login {"phone":"13686869696","password":"123456"} → success=true, token=152c5a021c744db4903dd8a7d84b9a10

## 3. 列表 attach 字段（三条接口均为匿名访问）OK
- GET /attraction/of/type?typeId=1&current=1 → 5 条（7 断桥/8 苏堤/13 龙井村/16 满觉陇/21 鼓浪屿），每条均含 avgScore/ratingCount/checkinCount/hasTicket/hasSeckill
- GET /attraction/of/name?name=西湖 → 西湖风景名胜区(1)：avgScore=45.7 ratingCount=7 checkinCount=3 hasTicket=true hasSeckill=true
- GET /attraction/of/destination?city=厦门 → 6 条（21 鼓浪屿/22 厦门大学/23 环岛路/24 曾厝垵/25 南普陀寺/26 沙坡尾），字段齐全

## 4. 评分一致 OK（列表 avgScore vs 详情 avgScore）
| 景点 | 列表 | 详情 | 一致 |
|---|---|---|---|
| 西湖(1) | 45.7 | 45.7 | OK |
| 断桥(7) | 45.0 | 45.0 | OK |
| 千岛湖(17) | 47.0 | 47.0 | OK |
| 鼓浪屿(21) | 46.0 | 46.0 | OK |

## 5. 免费/门票/秒杀标记 OK
- 西湖(1)：hasTicket=true, hasSeckill=true OK（普通票+秒杀票）
- 断桥(7)：hasTicket=false（免费）OK
- 千岛湖(17)：hasTicket=true, hasSeckill=true OK（秒杀票）
- 鼓浪屿(21)：hasTicket=false（免费）OK

## 6. 打卡数一致 OK（列表 checkinCount vs 详情 checkinCount）
- 西湖(1)：3 vs 3 OK ｜ 断桥(7)：2 vs 2 OK ｜ 千岛湖(17)：0 vs 0 OK ｜ 鼓浪屿(21)：2 vs 2 OK

## 结论
6 项验证全部 OK 通过。列表接口已 attach 动态评分/打卡/门票，且与详情同源一致（avgScore 公式一致、checkinCount 一致）。后端保持运行中。