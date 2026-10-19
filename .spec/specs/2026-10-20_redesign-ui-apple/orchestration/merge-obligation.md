# 分支拓扑与合并义务（收尾时必读）

## 现状（2026-10-20）

- 本轮分支 `spec/2026-10-20_redesign-ui-apple` 由 init_spec_package 从 **main（92bc862）** 分出——
  按 spec 契约"从最新 main 建分支"，这是正确行为，但导致**上一轮的全部成果不在本分支基底里**。
- 上一轮成果（606f50c）安全存放在 `spec/2026-10-19_upgrade-ui-design` 分支，包含：
  - 第一轮 UI 打磨（36 项，DESIGN.md v1 + spec 归档）
  - 环境修复：pnpm-workspace.yaml（shamefullyHoist + allowBuilds）、@iconify/utils、@playwright/test devDep、
    lint 债务 TODO(lint-debt) 豁免、global.css body 文字色兜底
  - 后端修复：EmbeddingClient 维度参数单复数（dimensions）
  - docs/runbook.md、docs/docker-compose.yaml macOS 适配
- 门禁修复员（dwfq-d35ae2ac-1 升级）已获批准在本分支重新应用 yaml 修复与 @playwright/test devDep，
  因此两分支的**环境修复部分**将逐字节一致、合并零冲突。

## 收尾合并义务

第一轮重设计提交前，必须：

```
git merge spec/2026-10-19_upgrade-ui-design
```

冲突裁决原则：

1. **视觉样式冲突**（chat-message.vue、settings.ts、global.css、search-*、theme-drawer 等）：**重设计侧胜出**——
   它取代第一轮观感，第一轮的这些改动只作历史。
2. **后端/环境/文档冲突**（EmbeddingClient.java、runbook.md、docker-compose.yaml、pnpm-workspace.yaml、
   package.json）：**第一轮侧胜出**（本分支已按批复重新应用的部分天然一致）。
3. **必须逐项核对第一轮带来的关键修复在本分支存活**：
   - `git grep '"dimensions"' -- src/main/java/com/mindbase/client/EmbeddingClient.java`（后端维度修复）
   - `git grep "shamefullyHoist" -- frontend/pnpm-workspace.yaml`
   - `git grep "@playwright/test" -- frontend/package.json`
   - `git grep "base-text-color" -- frontend/src/styles/css/global.css`（若重设计改写了 body 文字色方案，以重设计为准，但必须有显式文字色兜底）
4. 合并后全量门禁：typecheck + eslint 0 error + impeccable detect + 浏览器目检。

## 遗留（反馈池）

- **后端**：断线重连时若生成已完成，ws2 拿到 COMPLETED 快照但收不到终结事件
  （scripts/chat-reconnect-smoke-test.mjs 超时于 ws2 completion）。不在 UI 范围，单独排查。
- **配额**：admin 初始 Token 配额过小（首轮对话即耗尽），本地已在 Redis 手工充值
  （user:token:llm:1 / user:token:embedding:1 = 5000000）；正式方案走充值接口或调大初始配额。
