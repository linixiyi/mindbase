# 2026-11-10_fix-eval-and-runtime-gaps - Completion Summary

## Delivery
- task-0001 评委独立性与 κ 门禁生效
- task-0002 评测成本与 token 记账
- task-0003 gold 文件级 md5 与分块级 id
- task-0004 真机冒烟门禁
- task-0005 阈值曲线脚本
- task-0006 ReAct 上下文预算与引用去重
- task-0007 引用校验语义
- task-0008 分块重叠不进正文
- task-0009 Kafka 白名单与取文件收口
- task-0010 生产与评测共用 ChatPipeline 装配
- task-0011 评委双采样与一致性判定
- task-0012 评测数据集审计脚本
- task-0013 人工复核导出/回填 CLI
- task-0014 RRF 分路权重
- task-0015 RerankClient WebClient 复用
- task-0016 JWT 日志脱敏
- task-0017 清库密钥外部化
- task-0018 上传文件签名校验
- task-0019 客户端 IP 信任策略
- task-0020 优雅停机配置
- task-0021 ES mapping 显式 public 字段与审计脚本
- task-0022 OCR 可用性预检
- task-0023 旧分块重叠断言迁移新语义
- task-0024 评测 token 用量断言迁移合并口径
- task-0025 ES mapping 追加断言迁移新模板口径

## Evidence
- Final-tree verifies passed; see runs.jsonl.

## Decisions
- D1 |  不引入 Python 评测框架与付费 SaaS，指标与统计继续用 Java 自建，Python 只做离线脚本 | by: V1 | reversal: checkpoint revert
- D2 |  本轮不改前端、不改 CI workflow、不新增运行时依赖 | by: V5 | reversal: checkpoint revert
- D3 |  task-0008 交付（正文不再带重叠前缀）与既有断言 ParseServiceUnitTest.testSplitTextIntoChunksWithSemantics_AddsSentenceOverlap:173 互斥；授权迁移该单测断言到新语义，扩边由 emergent task-0023 承接该文件 | by: V2 | reversal: checkpoint revert
- D4 |  task-0002 交付（case 级 tokenUsage 改为生成 + 评委合并口径，EvalCaseResult.TokenUsage.merged + RagEvalCaseRunner 合并写入）与既有断言 EvalReActPipelineTest.java:146/:193、EvalSmokeFixtureTest.java:219-222（旧口径"评委调用不计入"）互斥；授权按新口径迁移这三处断言（case 级含评委：reactModeRunsProductionLoop 33/18/51、reactModeAccumulates 33/23、冒烟首例 51，报告级 19 题重算 627/342/969），扩边由 emergent task-0024 承接 EvalReActPipelineTest 与 EvalSmokeFixtureTest 两个测试文件 | by: V2 | reversal: checkpoint revert
- D5 |  eval_gate.sh 离线门禁对 fixture 报告（SELF_JUDGE/releaseEligible=false）加 --allow-self-judge 放行：沿用 task-0001 既有显式降级通道，定位为离线回归检测而非发布判定；A/B 实测确认 7 条 --fail-if 阈值与 Wilson 门禁未放宽（不加旗标则 done 标准"eval_gate.sh 全绿"在桩评委前提下无解） | by: V2 | reversal: checkpoint revert
- D6 |  task-0011 卡面 repeat"默认 true"若落在 Java 字段默认，会使 11 个直接构造 RagEvalProperties 的既有用例（单评委回复队列，跨 5 文件）失败；裁决 Java 字段默认 false、application.yml ${EVAL_JUDGE_REPEAT:true} 一字不动——生产默认仍 true（卡面生产语义由 yml 承担），单测走单采样路径，双采样由 JudgeRepeatConsistencyTest 专测（显式 setRepeat(true)）；5 个 boundary 外测试文件不动 | by: V2 | reversal: checkpoint revert
- D7 |  task-0011 卡面"两次一致取均值（1 位小数）"与 Integer score 字段（JudgeVerdict/EvalCaseResult，均在 boundary 外）冲突；裁决：先按 1 位小数求均值，再四舍五入（half-up）落 Integer judgeScore；"一致但分数不同"的样本 judgeReason 写 A=…/correct; B=…/correct; mean=x.x→N 自文档化（原始样本完整可复核）；不改报告 JSON schema（V6 keep-public-api） | by: V2 | reversal: checkpoint revert
- D8 |  task-0021 在 mapping 增显式 public 字段（cf2c20e）使既有断言 KnowledgeIndexManagerTest.onlyMissingNonVectorFieldsAreAppendedToExistingIndex:54 互斥（模板 12 字段→旧索引缺失=2，原断言 expected 1）；授权把断言迁移到新模板口径（missing=2 且含 chunkContext 与 public，幂等断言同步补 public），fixture 保持"旧索引"语义不动；扩边由 emergent task-0025 承接该测试文件（复审两轮同一发现，修法与 0023/0024 先例一致） | by: V2 | reversal: checkpoint revert
- D9 |  done 终局门禁发现 task-0010 卡面 boundary 清单漏列其交付明文要求的新建文件 ChatPipelineFactory.java（"抽 ChatPipelineFactory 统一装配"），导致 boundary_audit 判越界；修正为把 new:src/main/java/com/mindbase/service/ChatPipelineFactory.java 补入 task-0010 boundary——包定义勘误而非扩权，该文件本就是 task-0010 的交付物本体 | by: V2 | reversal: checkpoint revert

## Parked

## Emergent tasks
- task-0022 OCR 可用性预检
- task-0023 旧分块重叠断言迁移新语义
- task-0024 评测 token 用量断言迁移合并口径
- task-0025 ES mapping 追加断言迁移新模板口径

## Lessons
- See .spec/lessons.md for retained project lessons.
- Archived: 2026-11-10T22:22:10+00:00

## Git
- Commit interval: d390d708a898154210b46711567ac5738a3524ca
