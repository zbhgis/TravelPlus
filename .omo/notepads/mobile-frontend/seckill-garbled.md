# 西湖秒杀乱码调查

## 结论
乱码出现在**数据库层**：`tb_ticket` id=10（西湖景区秒杀票）的 title/sub_title/rules 被**双重 UTF-8 编码**污染。接口原样透传，前端模板正常。**不是**前端硬编码文字问题。

## 证据
1. tb_attraction id=1 名称 = 西湖风景名胜区（正常）。
2. tb_ticket（列名为 title 非 name）：id=1 title=西湖游船体验票（正常，type=0）；**id=10 title=`è¥¿æ¹–æ¸¸èˆ¹ç§'æ€€ç¥¨`（乱码，type=1 秒杀）**，pay=1分，create_time=2026-08-01。
3. 双重编码证据：`西`(UTF-8 E8 A5 BF) 在 id=10 中存储为 `C3 A8 C2 A5 C2 BF`；用 CP1252 还原字节后解码 = 「西湖游船秒杀票」，sub_title=「限时特惠」，rules=「每人限购1张」。
4. tb_seckill_ticket：ticket_id=10，stock=9，begin 2026-08-01 00:00:00 ~ end 2026-08-31 23:59:59（时间正常）。
5. 接口透传：`curl http://localhost:8081/ticket/list/1` 响应 title 与 DB 一致（乱码），后端未修复。
6. 前端 `travelplus-frontend/js/pages/attraction.js`（注意：不是 attraction-detail.js）：L418-435 原样渲染 `t.title`/`t.subTitle`；`秒杀`按钮(L433)、`秒杀成功/失败` toast(L448/464) 均为正常 UTF-8 硬编码。前端无转码逻辑，直接展示接口返回值。

## 根因
id=10 记录写入时发生双重编码（UTF-8 字节被按 CP1252/Latin-1 误读后再次以 UTF-8 存储），疑似 SQL 客户端/脚本字符集设置错误。

## 修复建议（未执行）
- 数据修复：`UPDATE tb_ticket SET title=CONVERT(CAST(CONVERT(title USING latin1) AS BINARY) USING utf8mb4) WHERE id=10;`（需先在小库验证），sub_title/rules 同。
- 根因：检查写入通道 character_set_client/connection/results。
