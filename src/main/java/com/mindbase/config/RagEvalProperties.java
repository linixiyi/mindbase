package com.mindbase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线 RAG 评测配置：检索看 Recall@K / MRR / nDCG@K，生成看 LLM-as-a-judge 打分，按比例抽样给人工复核。
 */
@Data
@Component
@ConfigurationProperties(prefix = "eval")
public class RagEvalProperties {

    /** nDCG@K 的默认截断 K 列表；聚合器未显式传入 K 时也使用该值，两处口径保持同源 */
    public static final List<Integer> DEFAULT_NDCG_KS = List.of(3, 5, 10);

    /** 评测生成链路：react（默认，走生产 ChatPipeline 的 ReAct 轮次/工具调用/引用装配）或 rag（检索单轮直答，历史口径） */
    private String mode = "react";
    /** 评测报告（JSON）输出目录 */
    private String outputDir = "eval-reports";
    /** 通过 datasetPath 指定数据集时只允许读取该目录下的文件；也可以用 classpath:eval/xxx.jsonl */
    private String datasetDir = "eval-datasets";
    /** 随机抽样给人工复核的比例（评委输出解析失败的用例总是需要复核） */
    private double humanReviewSampleRate = 0.1;
    /** 抽样随机种子，为空时每次随机；固定种子便于两次评测抽到同一批用例 */
    private Long randomSeed;
    private int defaultTopK = 5;
    private int maxTopK = 20;
    /** 检索排序指标 nDCG 的截断 K 列表（支持 3/5/10，去重升序输出）；汇总按列表逐个输出 nDCG@K */
    private List<Integer> ndcgKs = DEFAULT_NDCG_KS;
    /** 单次评测最多用例数 */
    private int maxCases = 500;
    /** 生成回答的最大输出 tokens；推理模型的思考过程也计入这个预算，给小了会只思考不输出 */
    private int generationMaxTokens = 4000;
    /** 评委模型的最大输出 tokens，同样要给思考过程留出余量 */
    private int judgeMaxTokens = 2000;
    /** 输出为空且因长度截断时，重试把预算翻倍，但不超过该值 */
    private int maxRetryTokens = 8000;
    private int generationTimeoutSeconds = 90;
    private int judgeTimeoutSeconds = 60;
    /** 送给评委的每条检索证据最大字符数 */
    private int maxEvidenceChars = 800;
    /** 生成和评委调用使用的请求方 ID，以 system 开头可豁免用户配额；检索仍以发起评测的管理员身份执行 */
    private String requesterId = "system-eval";

    /** 评委模型配置：走独立供应商时的定向参数；provider 未配置时评委与生成共用主链路（SELF_JUDGE） */
    private Judge judge = new Judge();

    /** 评委与人工一致率的告警阈值 */
    private HumanAgreement humanAgreement = new HumanAgreement();

    @Data
    public static class Judge {
        /** 评委使用的供应商编码（llm scope 下管理端配置的 provider，不要求处于激活状态）；空表示未配置独立评委供应商，评委与生成共用主供应商，报告标 SELF_JUDGE */
        private String provider;
        /** 评委模型名；为空时使用该供应商配置里的默认模型 */
        private String model;
    }

    @Data
    public static class HumanAgreement {
        /** 评委与人工在 correct 上的一致率低于该值时，评测汇总标记为需要重新校准 */
        private double minAgreementRate = 0.8;
        /** Cohen's kappa 下限（默认 0.6：扣掉碰巧一致后的一致性低于它说明评委不可信）；置空则不做 kappa 门禁 */
        private Double minKappa = 0.6;
        /** 评委和人工都给出结论的用例少于该数量时只提示样本不足 */
        private int minComparableCases = 5;
    }
}
