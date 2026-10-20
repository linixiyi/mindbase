# 修复验证记录 · backlog#11

## 压测协议
- 负载：主题切换按钮点击（scheme 单步循环）→ 700ms → SPA 导航 → 观察
- 断言：t400（+420ms）与 t1300（+1300ms）两时点 main.innerHTML 长度 > 1000

## 修复前（对照）
- 压测 A：6 轮，第 6 轮 model-provider 空白（render=54）
- 压测 B：14 轮协议，第 6 轮同位空白；新对话点击后空白（render=54）

## 修复后
- 压测 C（同口径 14 轮）：**0 瞬态空白 / 0 最终空白**（14/14 ok，含原必现点）
- 新对话路径：点击 → 会话切换 → 欢迎区 + 输入框正常（render≈13k，.chat-welcome 存在）
- morph：主题切换圆形揭示正常触发，暗色生效
- 暗色导航：usage-monitor / chat 双页 dark+rendered
- 截图：post-fix-chat-dark.png、newchat-flow-check.png
