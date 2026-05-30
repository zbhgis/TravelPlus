# 测试残留数据清理摘要

日期：2026-08-11
范围：TravelPlus 后端（MySQL docker travelplus-mysql 3307 root/123456，库 travelplus）测试残留数据清理，恢复功能开发前干净状态。

## 清理内容

- `tb_chat_message`：删除 `id >= 108` 共 **5 条**（108 左滑功能验证消息、109/110 造会话用、111/112 客服回复）。
- `tb_chat_hidden`：清空 **4 条**（1010→1、1→1010、1→1011、1010→1011，测试删除会话产生）。
- `tb_chat_pin`：清空 **2 条**（user_id=1 置顶 peer 1010 与 1021，测试置顶产生）。
- 未删除 id<108 的真实历史消息（保留 id 11~106 共 68 条），未改代码、未 git、未动其他表。

## 执行方式

- 临时 SQL 文件 UTF-8 无 BOM，经 `Get-Content -Raw | docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus` 管道执行
  （PowerShell 5.1 不支持 `<` 重定向，见 chat-pin-backend.md 经验）。

## 验证结果

- 删除后计数：`tb_chat_message id>=108` = 0、`tb_chat_hidden` = 0、`tb_chat_pin` = 0；`tb_chat_message` 剩 min 11 / max 106 / total 68。
- 回归验证（登录 13912345678/123456，端口 8081，token 放 authorization 头）：
  - `GET /chat/sessions` → success，正常返回（剩 peer=1 真实会话，无 401）。
  - `PUT /chat/pin/1` → success；sessions 中该会话 isPinned=true；`DELETE /chat/pin/1` → success；isPinned 恢复 false。
  - `GET /chat/unread-count` → success，data=1（真实未读数）。
  - 置顶往返验证后再清一次 `tb_chat_pin` → 计数 0。
- 前端快检：**跳过**——travelplus-frontend dev server 未运行（8080 端口是 MindBridge 项目）；后端 API 回归已验证，前端渲染风险低。

## 关键经验

- 登录接口 `POST /user/login`（RequestBody {phone, password}），成功后 data=token，后续请求放 `authorization` 请求头（RefreshTokenInterceptor 读取）。
- 服务实际端口 **8081**（application.yaml server.port），8080 是其他项目占用，勿混淆。
