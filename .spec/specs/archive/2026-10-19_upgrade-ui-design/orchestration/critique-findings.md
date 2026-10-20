# Critique 发现清单（工作流产出，主会话合并）

来源：工作流 dwfrun-5d0db5b3-a1d8-41e9-9de3-ea4b9aeb9cbf「分区域并行 critique」阶段，四名评审员
（聊天 / 问答历史 / 知识库 / 全局壳层）对源码 + DESIGN.md + craft-floor.md 的代码级评审。

## 总量与分级

- 原始发现共 **48 条**：P0 × 2，P1 × 9，P2/P3 × 37。
- P0/P1 全部列出如下（11 条），每条均已在「汇总去重并制定修复计划」阶段处置。
- P2/P3 经汇总去重后：15 条进入修复计划（含并入了 P0/P1 的修复项），10 条列入「本轮不修」，
  其余在去重中合并或按预算（总量上限 15 条，P3 → P2 顺序砍除）舍弃。

## P0（破坏可用性或明显不协调）

1. **知识库文件名列图标尺寸失控**（`frontend/src/views/knowledge-base/index.vue`）
   - 问题：文件类型图标用 class `text-12`，经项目 UnoCSS 预设实测解析为 `font-size: 3rem`（48px），
     svg-icon 按 1em 渲染即 48px，比 14px 行内文字大 3 倍多，撑爆 `size=small` 表格行；
     同文件 MD5 列 `text-3` 解析为 12px，裸数字写法同文件内自相矛盾。
   - 处置：已修复（knowledge-base 分片，改用明确 px 尺寸档）。

2. **检索结果 Score 标签遮挡正文且自隐**（`frontend/src/views/knowledge-base/modules/search-dialog.vue:128-134`）
   - 问题：标签绝对定位悬在正文上、正文未预留空间，首行文字从标签底下穿过；
     `hover:bg-transparent hover:color-transparent` 让标签 hover 时完全消失（用自隐掩盖遮挡）；
     90% 实心主色标签违反「一屏之内靛紫永远是少数派」；draggable 属性给出无意义拖拽暗示。
   - 处置：已修复（knowledge-base 分片，标签改规范淡色形态、正文预留空间、去除 hover 自隐与 draggable）。

## P1（显著影响观感）

3. **问答历史加载态与空态同时渲染**（`frontend/src/views/chat-history/index.vue:121`）
   - 问题：`v-if="!list.length"` 在初次加载（loading=true 且 list=[]）时即为真，
     NSpin 蒙层下透出「暂无数据」；chat-list.vue 已解决过同一问题但本页未移植。
   - 处置：已修复（chat-history 分片，引入 `showEmpty` computed）。

4. **问答历史空态形态偏离规范且缺筛选引导**（同文件）
   - 问题：默认 NEmpty 插画 + 泛化「暂无数据」，不符合 DESIGN.md 空态固定组合
     （80px 主色 8% 圆角砖 + 图标 + 15px 主文案 + 12px 次提示）；筛选无结果时不区分文案、无重置出口。
   - 处置：已修复（chat-history 分片，规范空态组合 + hasActiveFilters 区分「无匹配记录」+ 重置筛选按钮）。

5. **检索高亮用 primary-400 白字，对比度 1.78:1**（`search-dialog.vue:123`）
   - 问题：`bg-[rgb(var(--primary-400-color))] + color-white` 远低于 4.5:1；
     `rd-sm` 实测 2px，低于圆角刻度最小档 4px。
   - 处置：已修复（knowledge-base 分片，改规范淡色高亮与合规圆角）。

6. **文档预览模态壳 32px 圆角**（`knowledge-base/index.vue:649`）
   - 问题：超出 DESIGN.md 组件圆角上限 16px 一倍，与浮层圆角语言脱节。
   - 处置：已修复（knowledge-base 分片，归位 16px）。

7. **上传弹窗错误/提示文案裸 hex 绕开 token 且对比度不足**（`upload-dialog.vue:136-162`）
   - 问题：`text-#ef4444` 亮色 3.76:1、`text-gray-500` 暗色 3.53:1，12px 文案均低于 4.5:1；
     `#ef4444` 也不是规范错误红 `#d03050`，同模块出现两种「错误红」。
   - 处置：已修复（knowledge-base 分片，统一规范错误态 token）。

8. **检索结果列表间距倒挂 + 嵌套卡片 + 证据不可溯源**（`search-dialog.vue:110-139`）
   - 问题：`my-8` 实测 32px 反超应用区块间距 24px；弹窗内再套整装卡片构成嵌套卡片；
     「来源：文件名」纯文本未走证据链接语言，也没接父页已有 FilePreview 模态。
   - 处置：已修复（knowledge-base 分片，间距归位、去嵌套、来源改引用样式）。

9. **全局搜索结果行硬编码满灰底 + 未定义暗色 token**（`global-search/components/search-result.vue:37-41`、`search-modal.vue:107`）
   - 问题：每行 `bg-#e5e7eb` 实心条、选中行内联实心主色块+白字，是全壳层主色密度最高、
     最违反「主色做选中态用 8% 透明」的表面；`dark:bg-dark` 引用 uno theme 未定义颜色（不生成 CSS），
     暗色下浅灰行浮在深色弹窗上；行圆角 4px 低于组件基准；prefix 图标硬编码非 token 灰。
   - 处置：已修复（theme-shell 分片，改主色 8% 选中态、可用暗色 token、圆角与图标色归 token）。

10. **浮动侧栏 shadow-2xl 静止重影 + 两套矛盾阴影声明**（`packages/materials/src/libs/admin-layout/index.vue:170`、`global-sider/index.vue:23`、`theme/settings.ts:36`）
    - 问题：全壳层最大静止表面携带最重 `shadow-2xl`，不在阴影词汇表内；
      settings.ts 的 sider 阴影 token 被 aside 的 `of-hidden` 裁剪成死代码。
    - 处置：部分完成——`@sa/*` 包文件不在本轮任何分片边界内（见 DESIGN.md「已知例外」）；
      settings.ts 两枚死阴影 token 已注记死因（物理删除被 `app.d.ts` 类型必填拦住，待类型放开）；
      DESIGN.md 阴影词汇表已同步死 token 与已知例外。

11. **顶栏图标混用五个图标库**（`global-search/index.vue:13`、`full-screen.vue:18-19`、`theme-schema-switch.vue:32-36`、`theme-button.vue:14`、`user-avatar.vue:75`）
    - 问题：uil / gridicons / material-symbols / majesticons / phosphor 混用，线宽与实心/描边不一致，
      违反 craft-floor 图标一致性要求。
    - 处置：已修复（theme-shell 分片，壳层门面图标统一为 material-symbols 线性风格；user-avatar 深层
      菜单项如仍有个别异源图标，属后续小项）。

## 处置汇总

- 修复计划：15 条（chat-history 6 / knowledge-base 5 / theme-shell 4），实际完成时各分片将同分片的
  关联 P2 项一并处理（chat-history 分片完成 7 项、knowledge-base 18 项、theme-shell 11 项）。
- 「本轮不修」清单（10 条）见工作流交付物《MindBase UI 升级报告》；代表性条目：
  `packages/materials` 的 shadow-2xl 本体（边界外）、需要真实后端数据才能验证的项、纯文案润色类。
- 主会话浏览器目检追加发现并修复 1 条：亮色模式下 `body` 未设显式文字色，裸文本继承 UA CanvasText
  （系统暗色时为白色）导致会话侧栏「对话列表」标题不可读——已修（`global.css` body 显式
  `rgb(var(--base-text-color))`，亮暗双态验证通过）。
