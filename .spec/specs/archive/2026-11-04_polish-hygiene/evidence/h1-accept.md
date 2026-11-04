# H1 主会话抽验（2026-11-05 凌晨，无头 Playwright）

- 键帽：顶栏触发钮实测显示「⌘ K」（macOS 平台判定生效）✅
- 整钮可点：中心/右缘点击均开面板；左缘 x+4 初测失败系**登录成功通知卡（n-notification，x=1059 w=365）瞬态遮挡**（elementFromPoint 实证），非按钮缺陷——H1 修复成立 ✅
- mvn test 全量 329/329（复审另做 HEAD 基线对照：HEAD 6 error 被本包修复，+1 新用例、零 @Ignore/Skip 掩盖）
- README 链接 curl 200（复审核实）
- 后端「模型配置」grep 零残留、refreshToken 401 链 7/7 单测（复审核实；端到端禁用分支留待后续轮顺带）
