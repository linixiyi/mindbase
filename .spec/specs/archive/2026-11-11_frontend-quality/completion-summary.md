# 2026-11-11_frontend-quality - Completion Summary

## Delivery
- task-0001 流式归约抽纯函数并补 7 类帧测试
- task-0002 chunk 批量 flush，消除逐帧全量重渲染
- task-0003 历史加载单源 + 在途请求取消
- task-0004 停止语义与独立停止按钮
- task-0005 无障碍：键盘可达、aria-live、按钮名称
- task-0006 i18n 止血：fallback 改 zh-CN + 中文硬编码基线门禁
- task-0007 请求层清污：移除模板 token 与生产调试日志
- task-0008 聊天请求收口 service/api + WS 帧判别联合
- task-0009 列表稳定 key 与滚动回调清理
- task-0010 引用链接抽纯函数并补转义回归测试
- task-0011 Playwright 冒烟：config、去硬编码、2 条用例
- task-0012 前端本地门禁脚本与执行文档
- task-0013 构建产物分包与体积预算

## Evidence
- Final-tree verifies passed; see runs.jsonl.

## Decisions
- D1 |  不新增依赖：测试只用已装的 vitest + jsdom，e2e 只用已装的 @playwright/test（V5）。
- D2 |  不改后端 WS 协议：0001 的帧类型只描述前端已消费的字段；seq/去重留给后端轮次。
- D3 |  0005 与 0009 的 a11y/键位门禁用 grep 脚本而不是引入 eslint 插件，避免依赖变更。
- D4 |  0006 只锁定基线，不做全量翻译；`--update-baseline` 需人工在 PR 说明里写理由。
- D5 |  包定义勘误（同评测包 D9 先例）：0002 交付与成功判定明文要求改 chat-list.vue 滚动 watch、0008 交付④明文要求 typings 增帧类型（实际路径 frontend/src/typings/api.d.ts）、0011 交付③明文要求旧 spec 移到 e2e/kb-column.spec.ts，三者均未列于各自 boundary 清单——按交付补入对应卡 boundary，非扩权 | by: V2 | reversal: checkpoint revert
- D6 |  task-0004 交付④（stop 语义改 stopped）与 task-0001 的过渡产物 reducer.spec.ts:85（卡面明文"本卡保持 finished，留给 0004"）互斥；且 api.d.ts:507 status 联合无 stopped 时 chat-message.vue 的 stopped 比较触发 TS2367、typecheck 必红——授权两处并入 task-0004 boundary：reducer.spec.ts:85 断言迁移为 stopped（保留"stop 不改内容"意图）、api.d.ts status 联合仅追加 'stopped'（加法不破坏既有消费），不用局部类型拓宽或 as 强转绕过 | by: V2 | reversal: checkpoint revert

## Parked

## Emergent tasks

## Lessons
- See .spec/lessons.md for retained project lessons.
- Archived: 2026-11-11T00:37:02+00:00

## Git
- Commit interval: 94aa02a382c888d49d706809a53d1c5afa4158ad
