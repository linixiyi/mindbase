# UI 美化计划（impeccable 八指令 · autorun 链）

> 来源：用户会话 brief——「利用 $spec :autorun 设置工作流调用 $impeccable 的 animate / bolder / colorize / delight / layout / overdrive / quieter / typeset 美化 UI」。
> 世界观：第四轮已确立的「晴空玻璃（Azure Glass · #0071E3）」（DESIGN.md 为准）；本轮系列在其上做**体验深化**，不改世界观。
> 链规则：autorun 递归链——每轮一个 spec 包覆盖一组连贯特性，run→check→done 后勾选对应特性，全部勾选后链完成。
> 运行模式：主会话内链（ZCode 无 spawn CLI，fresh-session 由主会话延续替代，已在 chain.json 记录）；push 阶段执行本地合并到 main，远程 push 按 AGENTS.md 硬规则待用户明确授权。

## 特性清单

- [x] colorize+quieter：浏览器表面色彩收口——::selection 选区色、输入 caret-color、滚动条、focus-visible 焦点环统一走 token（craft-floor「built not assembled」信号），并做一次强调色面积/噪音复核
- [x] layout+typeset：管理页页头区层级——页题与主行动同行成区（page-header 区块），表格空态排版，区块节奏按 DESIGN.md 复核修正
- [x] animate+delight：聊天空态 welcome——新对话空态的方向性欢迎区（产品语言、建议问法、单一入场动效），从产品机制派生、可百次使用
- [x] bolder+overdrive：签名面信念度——recharge 余额区与 personal-center 档案头的层级强化（系统语汇内放大），外加一个 Operate 手感点（global-search 命令面板化入场：@starting-style 即时反馈，无依赖、优雅降级）
- [ ] 收口验证：全量 impeccable detect 零 error + 真机亮暗走查零 P0/P1 + DESIGN.md 与实现一致性复核，产出收口复审并宣布链完成
