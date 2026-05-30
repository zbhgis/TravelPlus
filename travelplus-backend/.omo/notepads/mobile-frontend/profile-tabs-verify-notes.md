# 用户主页分 Tab 接口验证记录

> 日期：2026-08-07 | 后端重启后全量验证（PID 30776，新 Maven spring-boot:run 实例）
> 账号：T1=13686869696（用户1 小鱼同学） / T2=13838411438（用户2 可可今天不吃肉）

## MvcConfig 放行细化验证
- ✅ GET /attraction/1（匿名）→ HTTP 200，返回西湖详情（/attraction/{id} 放行）
- ✅ PUT /attraction/like/1（未登录）→ HTTP 401（like 子路径不在放行列表，被 LoginInterceptor 拦截）

## 用户评论列表 GET /user/comments/{userId}?current=
- ✅ GET /user/comments/2（T1）→ data 含 {id:4, noteId:4, noteTitle:"西湖断桥｜白堤漫步半日游…", noteImage:"/imgs/blogs/4/0.jpg", content, createTime}，total=1
- ✅ GET /user/comments/1（T1）→ 2 条（游记21/6 的评论），字段同上；用户1 评论不缺量，未补发测试评论

## 收藏按用户
- ✅ T1 POST /favorite {note,4}、{attraction,17} → success（幂等）
- ✅ GET /favorite/list?userId=1&type=note（T2）→ 能看到用户1 收藏含游记4（title/image/createTime）
- ✅ GET /favorite/list?userId=1&type=attraction（T2）→ 含景点17 千岛湖
- ✅ GET /favorite/list?type=note（T1，userId 缺省）→ 返回本人收藏（与 userId=1 一致）
- ✅ GET /favorite/list?userId=99999&type=note → fail "用户不存在"

## 景点点赞（Redis ZSet 切换）
- ✅ T1 PUT /attraction/like/17 连续 3 次均 success（赞→取消→赞，幂等切换）
- ✅ 点赞态下 GET /attraction/17：T1 isLike=true，T2 isLike=false（未登录为 null）
- ✅ 未登录 PUT /attraction/like/17 → HTTP 401

## 赞过列表
- ✅ T1 PUT /note/like/5：首次为"取消"（该游记原已点赞，列表原含 4/5/6），再次 PUT 恢复点赞态；GET /user/liked-notes/1 → 含游记5（total=3，另含 6/4）
- ✅ GET /user/liked-attractions/1（T1）→ 含景点17（total=1）
- ✅ GET /user/liked-notes/2（T1）→ data=[]，total=0（用户2 未赞过）

## 清理
- ✅ 取消 T1 景点17 点赞（PUT 再点一次）→ GET /attraction/17 isLike=false；/user/liked-attractions/1 变空
- ✅ DELETE /favorite/attraction/17 移除测试景点收藏；游记4 收藏保留 1 条演示
- ✅ 游记5 点赞保留（与点赞通知一致）；无新增测试评论需删除

## 结论
- 全部 8 项接口行为符合预期：放行细化、评论列表字段、收藏按用户归属/缺省/越权校验、景点点赞幂等切换与 isLike 回显、未登录 401、赞过列表按用户隔离。后端保持运行。
