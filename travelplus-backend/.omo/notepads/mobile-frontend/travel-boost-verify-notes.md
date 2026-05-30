# TravelPlus 景点评论/动态评分/打卡足迹/城市推荐/游记关联景点 — 验证记录

> 来源：后端重启 + curl 全量接口验证（2026-08-07）。后端保持运行（8081），供 orchestrator 浏览器 E2E 复用。

## 环境与重启
- 杀 8081 监听(PID 41452 TravelPlusApplication) + maven 包装进程(32840) → cmd /c start-backend.bat 启动 → 轮询 /attraction-type/list 返回 200 ✅
- 登录用户1 T1（13686869696/123456）→ success, token=32a06e715af14b1683653ad233ddd7b6 ✅

## 城市推荐（匿名）
- GET /attraction/destinations → data=["杭州","厦门"] ✅
- GET /attraction/of/destination?city=厦门&current=1 → 6 景点（鼓浪屿/厦门大学/中山路/沙坡尾 等，含 x/y/score）✅
- GET /attraction/of/destination?city=杭州&current=1 → 每页10条，page2=10，合计20 个杭州景点（分页正常 MAX_PAGE_SIZE=10）✅

## 景点评论（T1）
- GET /attraction-comment/of/1?current=1 → seed 5 条，每条含 name(昵称)/icon(头像) ✅
- POST /attraction-comment {attractionId:21, content:"鼓浪屿真美"} → success；/of/21 新评论 id=23（userId=1, name=小陈同学, icon=.../1.png）置顶 ✅

## 动态评分（T1）
- GET /attraction/1 → avgScore=45.0(AVG×10=4.5)、ratingCount=6 ✅
- POST /attraction/rate {attractionId:26, score:5} → success；GET /26 → myRating=5, ratingCount=1, avgScore=50.0 ✅
- 改分 score:3 → success；GET → myRating=3, avgScore=30.0（可改分一人一票）✅
- score:9 → fail "评分需为1~5"（范围校验）✅

## 打卡足迹（T1）
- GET /attraction/21 → 用户1 seed 已打卡（checkedIn=true, checkinCount=2）✅
- POST /attraction/checkin/21 → success；重复 POST → success count 仍=2（幂等不重复计数）✅
- GET /user/checkins/1?current=1 → 6 条足迹（含鼓浪屿 id=21，带 x/y）✅
- GET /user/checkins/2（T1 查他人）→ 用户2 6 条足迹（沙坡尾/宋城/雷峰塔 等，带 x/y，他人可见）✅

## 游记关联景点（T1）
- POST /note {title:"关联测试游记", content:"测试", images:"", attractionId:21} → data:26 ✅
- GET /note/26 → 含 attraction:{id:21, name:鼓浪屿, image:/imgs/attraction/21/0.jpg} ✅
- POST /note 不带 attractionId → data:27 success（非必需字段）✅

## 清理（保留 seed）
- 删除测试游记 26/27 → success；复查 /note/26 → "游记不存在！" ✅
- SQL 删除鼓浪屿测试评论 id=23 → /of/21 恢复 seed 4 条 ✅
- 保留：用户1 对景点21 打卡（足迹演示）、景点26 评分 myRating=3、全部 seed 评论 ✅

## 结论
6 大功能模块全部 ✅ 通过；无 git 操作；后端保持运行。
