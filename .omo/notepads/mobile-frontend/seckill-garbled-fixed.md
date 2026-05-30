# 门票乱码修复记录

## 2026-08-08 tb_ticket id=10 双重 UTF-8 编码乱码修复

- 现象：`tb_ticket` id=10（西湖游船秒杀票）title/sub_title/rules 三字段双重 UTF-8 编码（mojibake），`西`(E8 A5 BF) 被存成 `C3 A8 C2 A5 C2 BF`。
- 扫描：全表 4 条记录，仅 id=10 受影响（id=1/21/22 正常）。
- 修复：`CONVERT(CAST(CONVERT(col USING latin1) AS BINARY) USING utf8mb4)`，ASCII 写 .sql 文件经 `docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4` 执行。
- 结果：title=西湖游船秒杀票，sub_title=限时特惠，rules=每人限购1张；接口 `GET /ticket/list/1` 返回正常中文。
- 无需重启后端。