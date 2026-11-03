# G5 产品自体验记录（2026-11-04 凌晨，无头 Playwright，1440×900）

## 实测结论

1. **登录→聊天全链路正常**：「一句话说明什么是RRF融合？」→ 完整回答 + 4 引用 chip + 检索工具链展示（截图 g5-chat.png）。
2. **pm-014 实锤**：Ctrl+K 无绑定（面板不开）；顶栏图标可开（placeholder「请输入关键词搜索」）；面板内搜「混合检索」→ **无数据**（只搜菜单标题，KB 内容不进面板）。
3. **新 bug**：控制台 3 次 `PAGEERROR: TypeError: Cannot read properties of undefined (reading 'toggle')`（登录→chat 导航过程触发）——转 G6 修复项。
4. 菜单 15 项齐（含四个治理入口）；术语现状确认：菜单「模型配置」 vs PRODUCT.md「模型供应商」（pm-029）。
5. **pm-022 真机闭环**（Playwright）：连打登录 30 次触发 429（retryAfterSeconds=2）→ 页面真实提交 → toast「登录请求过于频繁」持续在屏 → 按钮文案被倒计时态接管（截图 g5-429-countdown.png）。R1 复审的 medium 遗留就此解除。
6. 工具注：IAB 内嵌浏览器本会话彻底不可用（交互挂起），G5 全程改用仓库自带 Playwright 无头浏览器（更稳，后续轮沿用）。

## G6 聚焦（据此定稿）

- bug：`toggle` PAGEERROR 诊断修复
- pm-014：Ctrl+K 全局绑定 + 面板升级（菜单 + 知识库内容，复用 KB 检索既有端点）
- pm-029/030/031：术语与 IA 统一（模型供应商称呼统一、租户概念统一、菜单 order 与命名）
