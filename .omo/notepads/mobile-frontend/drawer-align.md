# 抽屉对齐修复笔记（drawer-align）

## 2026-08-11 桌面宽屏下抽屉对齐手机容器左缘

### 问题
桌面宽屏（≥768px）下 `.side-drawer` 使用 `position:fixed; left:0`，抽屉从浏览器最左侧滑出，而手机容器 `.app-container`（max-width:480px，margin:0 auto）居中，两者错位。

### 修复（travelplus-frontend/css/app.css，@media (min-width:768px) 内）
```css
.side-drawer {
  left: calc(50% - 240px);
  transition: transform 0.25s ease, left 0.25s ease;
}
.side-drawer:not(.show) {
  left: 0;
}
```
- `left: calc(50% - 240px)`：480/2=240，抽屉左缘对齐容器左缘（left 百分比基于 clientWidth，与容器 margin auto 居中基准一致，含滚动条时亦精确）。
- `:not(.show) { left: 0 }`：关键补充——仅改 left 会让关闭态停在 containerLeft-260px（如 132.5px），抽屉半屏可见！关闭态必须回到 left:0 + translateX(-100%) 才完全隐藏。
- `transition` 增加 `left`：否则关闭动画会先瞬移再滑出（可见跳变）。

### 验证（Playwright，数值测量）
| 视口 | 容器 left | 抽屉 open left | 对齐 | 关闭态 |
|------|-----------|----------------|------|--------|
| 1280 | 392.5 | 392.5 | diff=0 | right=0，完全隐藏 |
| 900 | 202.5 | 202.5 | diff=0 | - |
| 375（移动） | 0 | 0 | 贴屏左 | transition 仅 transform，未受影响 |

- 遮罩 `.side-mask` 保持全屏 inset:0，不动（点容器外同样可关抽屉，符合预期）。
- 移动端 <768px 零改动。
