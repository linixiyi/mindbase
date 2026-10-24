# F5 真机抽查记录（2026-10-24 19:0x，主会话，admin + 新注册 e2e-pm-user）

后端 8081 已带 F5 代码重启；菜单出现三个新治理入口（DLQ 第四入口由追补工作流落码中，本表不含）。

## 实证项

| 项 | 结果 |
| --- | --- |
| 冲突裁决 #/conflict-resolution | ✅ 真机可达：表格 + 空态「暂无疑似冲突」+ 教学文案 + 状态筛选，零错误 |
| 索引管理 #/knowledge-index | ✅ 真机可达：**线上真实索引名**（knowledge_base_aliyun_qwen_qwen3-embedding-8b_2048）、重建任务区、版本索引区 |
| 离线评测 #/rag-eval | ✅ 真机可达：发起评测表单（标签/topK/数据集来源双选/JSONL 粘贴）、运行列表 |
| pm-008 聊天记录分页 | ✅ `.n-pagination` 在位，`1 20 / 页`（服务端分页） |
| pm-012 防锁死（UI） | ✅ e2e-pm-user 行有「禁用」钮；admin 行无（NPopconfirm 弹层在本节流浏览器不挂载，改走端点直测） |
| pm-012 端点全链路（curl 直连 8081） | ✅ PATCH /admin/users/2/status {enabled:false} → 200「用户已禁用」；该用户登录 → **403「账号已被禁用，请联系管理员」**；重新启用 → 200；再登录 → 200；**禁 admin 自己 → 400「不能禁用当前登录的管理员账号」** |
| pm-004 冷启动判定（反向验证 ×2） | ✅ 判定正确：admin 文档 is_public=1 且 org_tag=default，新注册用户可检索到 → 正确展示静态建议（非空库）。真空分支（引导卡+去上传 CTA）未真机触发——需无 default 组织标签用户，DB 造数代价超收益；以实现读码（chat-list.vue:37-46 /documents/accessible totalElements 判空 + onActivated 重查）+ F5 复审代码级核实为准。临时翻 is_public=0 验证时该文档仍经 org_tag=default 可达，判定依旧正确，验后已还原 |

## 环境限制披露
- NPopconfirm 弹层与 IME 实机行为在内嵌浏览器不可验（rAF/合成节流）——pm-005 IME 防护、禁用按钮的弹层交互以代码级复审为准；端点语义经 curl 全链路实证
- 注册流程真机可用（邀请码 E2E-PM-2026 经 UI 注册成功 e2e-pm-user）——邀请码页面本身工作正常

## 测试数据处置
- e2e-pm-user（id=2，已重新启用）与邀请码 E2E-PM-2026（used_count=1）保留为可见测试痕迹；如需清理：DELETE FROM users WHERE id=2 与 DELETE FROM invite_codes WHERE code='E2E-PM-2026'

## 追补：DLQ 第四入口（2026-10-24 晚，后端重启加载新端点后）

| 项 | 结果 |
| --- | --- |
| 后端端点存活 | ✅ curl 无凭据 403（安全链覆盖）；admin token GET /admin/dead-letters → **200 空列表**（系统无死信，形状 {records,total,pages,current,size}） |
| 菜单注册 | ✅ 第 15 项「死信队列」（order 13，ADMIN 路由） |
| 视图真机 | ✅ #/dead-letter：页题 + 文件处理死信表 + 空态「暂无死信任务」+ 教学文案「文件处理任务重试耗尽会落到这里，由管理员排查后手动重放回处理链」 |
| 复审 low 项修复 | ✅ 详情弹窗重放成功后不关闭弹窗——主会话补 closeDialog 调用（状态块上移避 use-before-define），typecheck+eslint 双绿 |
| 重放操作真机 | ⚠️ 系统无真实死信，无法实测重放按钮链路；重放语义（复用 outbox 补发体系 + 409 防线 + requesterId 计费保持）经 DLQ 复审代码级逐点核实 |
