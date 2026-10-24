# UI 美化工作流链：spec 驱动 impeccable 八指令迭代 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）

## 阶段一：环境与 R1 工作流
- [x] 基线与运行态记录（分支 / dev server / 门禁基线）
  - id: task-env
  - boundary: 只记录事实，不改文件
  - verify: manual: 已采集——分支 spec/2026-10-24_beautify-ui-workflow；9527 HTTP 200；typecheck 绿；eslint 0 error/31 warning；detect --json 全量 `[]`；loading.ts:10 有 #646cff 残留（见 spec.md §2.1）
- [x] R1 工作流：勘察分工 → 八指令串行（layout/typeset/colorize/bolder/quieter/animate/delight/overdrive）→ 终局门禁 → 独立复审
  - id: task-r1
  - depends-on: task-env
  - boundary: 工作流子代理只改 `frontend/src/**`、`frontend/public/**`、`DESIGN.md`；每道工序自验 typecheck+eslint；脚本终局门禁四项；不碰后端/.env/依赖/引用标注数据流；不 commit
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && ! grep -rEq "5856D6|5B5BE8|646cff" src/ && ! /Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json src | grep -q '"error"'`
    - 运行目录：命令已自 cd
    - 前置条件：R1 工作流返回完成通知
    - 成功判定：exit 0（typecheck 绿 + eslint 0 error + 旧色残留零命中 + detect 无 error 级 finding）
    - 失败处理：失败项回写为 R2 聚焦面，R2 首任务修复
- [x] R1 报告回写 evidence/round-1-report.md
  - id: task-r1-report
  - depends-on: task-r1
  - boundary: 只写 `.spec/specs/2026-10-24_beautify-ui-workflow/evidence/`
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-24_beautify-ui-workflow/evidence/round-1-report.md`
    - 运行目录：任意（绝对路径）
    - 成功判定：exit 0
    - 失败处理：从工作流返回值补写
- [x] 主会话真机抽查：登录/注册页（后端不可用，仅未登录面）
  - id: task-browser-r1
  - depends-on: task-r1
  - boundary: 只读走查 http://localhost:9527（网络请求/控制台/目检），截图落 evidence/
  - verify: manual: 后端 8081 未运行，登录后视图不可达；走查记录并入 round-1-report.md 的"真机抽查"节

## 阶段二：迭代轮（R2+，按工作流反馈拉起，不限轮数）
- [x] R_n 工作流（n≥2）：聚焦上一轮 nextRoundFocus → 门禁 → 独立复审 → 报告
  - id: task-iter
  - depends-on: task-r1-report
  - boundary: 同 task_R1；每轮聚焦面取上一轮复审 verdict.nextRoundFocus；连续两轮 converged=true 或边际收益即止
  - verify: manual: 每轮 existence——`test -s .spec/specs/2026-10-24_beautify-ui-workflow/evidence/round-N-report.md` 且其中门禁节四项全绿；无报告的轮次不存在（轮次由反馈驱动，N 不可预知）

## 阶段三：收口
- [x] DESIGN.md 与实现一致性核查（结构级变化逐节比对）
  - id: task-design-sync
  - depends-on: task-iter
  - boundary: 只改 DESIGN.md（实现领先于文档时回写；文档正确而实现漂移记为发现并回修）
  - verify: `test -s /Users/linyixi/PaiSmart/DESIGN.md && ! grep -q "TODO\|TBD" /Users/linyixi/PaiSmart/DESIGN.md`
    - 运行目录：任意（绝对路径）
    - 成功判定：exit 0（存在且无未决占位）
    - 失败处理：逐节补写后复验
- [x] 收敛判定成文（末轮报告 + 完成摘要；Git done/push 授权留给用户）
  - id: task-converge
  - depends-on: task-design-sync
  - boundary: 只写本包文档
  - verify: manual: 收敛结论（converged 依据或停止理由）写入最后一份 round 报告；按 AGENTS.md 不主动 commit/push，done/push 待用户明确授权

## 阶段四：后端补验（用户 2026-10-24 授权起后端后追加）
- [x] Flash 子代理拉起 Redis/ES/Kafka/MinIO + 后端 8081，启动方式落记录
  - id: task-backend-up
  - boundary: 只启动本机既有服务与后端进程（nohup/docker -d 脱离会话）；不改仓库源码与配置；不打印 .env 值
  - verify: manual: 工作流 dwfrun-75373ad8 返回 8081 LISTENING；记录文件 evidence/backend-startup.md 存在且无真实凭证
- [x] 登录态真机走查：玻璃浮层/暗色/微动效（§12–§18 各节自记的真机待补项）
  - id: task-browser-loggedin
  - depends-on: task-backend-up
  - boundary: 只读走查 http://localhost:9527（登录态全部可达面）+ 截图落 evidence/screenshots/；不修改产品代码
  - verify: manual: 走查记录与截图回写 evidence/round-1-browser.md；覆盖清单——chip 对称性与 hover 受光、抽屉浮岛姿态、popover 族真实渲染（overlay 材质/14px/无 ghost ring）、lucide 图标链（命令面板/顶栏/表格操作条）、文件类型 tile 色、404 插画、按压律、引用 chip 悬停下划线、空态品牌徽标、rim 环与三档折射、滚动渐显发丝线、主题切换 morph、暗色全量（三层表面/rim 降档）

---

**当前进度**：由脚本计算，无需手动维护
