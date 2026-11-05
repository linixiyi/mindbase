# 打磨轮 H5：注册转化组（pm-032/033） - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- 新任务包使用 `YYYY-MM-DD_<verb>-<object>`，详见 `references/naming-and-commits.md`
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）
- 多会话/多 agent 共享时，任务可选加一行 `claim: <认领者> <ISO 时间>` 声明在做者；纯信息约定，非锁、无机器键、不进门禁，勾选即失效，归档保留

## 阶段一：实现
- [ ] 初始化最小项目骨架
  - boundary: 只创建必须目录、依赖和启动骨架
  - verify: `python3 -m pytest -q tests/test_skeleton.py`（替换为本项目真实初始化验证命令）
- [ ] 实现核心功能 A 的最小闭环
  - depends-on: task_0001
  - boundary: 只改该功能主链路所需模块
  - verify: `python3 -m pytest -q tests/test_core_feature_a.py`（替换为本项目真实测试命令）
- [ ] 打通关键链路
  - depends-on: task_0002
  - boundary: 只补集成所需粘合代码，不顺手重构
  - verify: `python3 -m pytest -q tests/test_critical_path.py`（替换为本项目真实集成验证命令）

---

**当前进度**：由脚本计算，无需手动维护