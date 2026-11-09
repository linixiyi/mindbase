package com.mindbase.service.eval;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * 评测统计口径的唯一 Java 实现（gate 与后续重述都以此为准，Python 侧只做阈值比较、不自产数字）：
 * <ul>
 *   <li>比例区间用 Wilson score interval，禁止用正态近似替代——小样本与极端比例下正态近似会越界且过窄
 *       （43/50 的正态近似 ≈ [76.4, 95.6]，Wilson ≈ [73.8, 93.1]）；</li>
 *   <li>同题两次评测的判对/判错配对结论用 McNemar 精确检验（二项精确双侧 p），不用卡方近似；</li>
 *   <li>同批题两次评测的分数差用配对 bootstrap（按题重采样，百分位 95% 区间），种子固定保证可复现。</li>
 * </ul>
 * 纯 JDK 实现，无第三方依赖；{@link #main} 提供最小 CLI 供 scripts/eval_gate.sh 调用。
 */
public final class EvalStatistics {

    /** 95% 两侧正态分位数 z_{0.975}，作为 Wilson 公式输入（这是分位数常数，不是"正态近似"本身） */
    public static final double Z_95 = 1.959963984540054;

    /** Wilson 置信区间，两端恒在 [0,1] 内 */
    public record Interval(double lower, double upper) {
    }

    /** 配对 bootstrap 结果：观测均值差与百分位 95% 区间 */
    public record BootstrapResult(double meanDelta, double ciLower, double ciUpper, int iterations, long seed) {
    }

    private EvalStatistics() {
    }

    /** 95% Wilson 区间；trials=0 时返回 null（与汇总口径一致：没有可计算样本就不编数字） */
    public static Interval wilsonInterval(int successes, int trials) {
        return wilsonInterval(successes, trials, Z_95);
    }

    /**
     * Wilson score interval：
     * (p + z²/2n ± z·sqrt(p(1−p)/n + z²/4n²)) / (1 + z²/n)。
     * 输入口径非法（successes 越界）抛 {@link IllegalArgumentException}。
     */
    public static Interval wilsonInterval(int successes, int trials, double z) {
        if (trials < 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException("successes/trials 非法: " + successes + "/" + trials);
        }
        if (trials == 0) {
            return null;
        }
        if (!(z > 0)) {
            throw new IllegalArgumentException("z 必须为正: " + z);
        }
        double p = (double) successes / trials;
        double z2 = z * z;
        double denominator = 1d + z2 / trials;
        double center = (p + z2 / (2d * trials)) / denominator;
        double half = (z / denominator)
                * Math.sqrt(p * (1d - p) / trials + z2 / (4d * trials * trials));
        return new Interval(Math.max(0d, center - half), Math.min(1d, center + half));
    }

    /**
     * McNemar 精确检验（双侧）：b/c 为两类单向翻转数（b=仅 baseline 对、c=仅 variant 对），
     * p = min(1, 2·P(X ≤ min(b,c)))，X ~ Bin(b+c, 1/2)。n=0（无不一致对）时 p=1。
     * 用对数域递推 ln C(n,i) = ln C(n,i−1) + ln((n−i+1)/i)，避免 C(n,i) 与 2^n 的上下溢，任意 n 都精确。
     */
    public static double mcnemarExactP(int b, int c) {
        if (b < 0 || c < 0) {
            throw new IllegalArgumentException("翻转数不能为负: b=" + b + ", c=" + c);
        }
        int n = b + c;
        if (n == 0) {
            return 1d;
        }
        int k = Math.min(b, c);
        double ln2 = Math.log(2d);
        double logTerm = 0d;
        double sum = 0d;
        for (int i = 0; i <= k; i++) {
            if (i > 0) {
                logTerm += Math.log((double) (n - i + 1) / i);
            }
            sum += Math.exp(logTerm - n * ln2);
        }
        return Math.min(1d, 2d * sum);
    }

    /**
     * 配对 bootstrap：对同一批题（下标即题目）有放回重采样 iterations 次，
     * 统计 mean(variant − baseline) 的百分位 95% 区间。种子固定 → 同一批输入得到同一区间，报告可复现。
     */
    public static BootstrapResult pairedBootstrap(double[] baseline, double[] variant, int iterations, long seed) {
        if (baseline == null || variant == null || baseline.length != variant.length) {
            throw new IllegalArgumentException("配对 bootstrap 需要两条等长的逐题分数");
        }
        if (baseline.length == 0) {
            throw new IllegalArgumentException("配对 bootstrap 需要至少一道题");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations 必须为正: " + iterations);
        }
        int n = baseline.length;
        double deltaSum = 0d;
        for (int i = 0; i < n; i++) {
            deltaSum += variant[i] - baseline[i];
        }
        double meanDelta = deltaSum / n;

        Random random = new Random(seed);
        double[] bootstrap = new double[iterations];
        for (int b = 0; b < iterations; b++) {
            double sum = 0d;
            for (int i = 0; i < n; i++) {
                int index = random.nextInt(n);
                sum += variant[index] - baseline[index];
            }
            bootstrap[b] = sum / n;
        }
        Arrays.sort(bootstrap);
        return new BootstrapResult(meanDelta, percentile(bootstrap, 0.025), percentile(bootstrap, 0.975),
                iterations, seed);
    }

    /** 线性插值百分位（位置 = q·(B−1)，与常见统计工具默认一致） */
    private static double percentile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int lo = (int) Math.floor(position);
        int hi = (int) Math.ceil(position);
        if (lo == hi) {
            return sorted[lo];
        }
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo);
    }

    /**
     * 最小 CLI：scripts/eval_gate.sh 等脚本以 Java 为唯一数字来源调用。
     * 用法：
     * <pre>
     *   java -cp target/classes com.mindbase.service.eval.EvalStatistics wilson &lt;successes&gt; &lt;trials&gt;
     *   java -cp target/classes com.mindbase.service.eval.EvalStatistics mcnemar &lt;b&gt; &lt;c&gt;
     * </pre>
     * 成功时输出单行 JSON（如 {@code {"lower":0.738138,"upper":0.930492}}）；用法错误退出码 2，无法计算退出码 1。
     */
    public static void main(String[] args) {
        if (args.length == 3 && "wilson".equalsIgnoreCase(args[0])) {
            int successes;
            int trials;
            try {
                successes = Integer.parseInt(args[1]);
                trials = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("参数必须是整数: " + args[1] + " " + args[2]);
                System.exit(2);
                return;
            }
            Interval ci = wilsonInterval(successes, trials);
            if (ci == null) {
                System.err.println("无法计算: trials=0 没有可计算样本");
                System.exit(1);
                return;
            }
            System.out.println(String.format(Locale.ROOT, "{\"lower\":%.6f,\"upper\":%.6f}", ci.lower(), ci.upper()));
            return;
        }
        if (args.length == 3 && "mcnemar".equalsIgnoreCase(args[0])) {
            int b;
            int c;
            try {
                b = Integer.parseInt(args[1]);
                c = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("参数必须是整数: " + args[1] + " " + args[2]);
                System.exit(2);
                return;
            }
            System.out.println(String.format(Locale.ROOT, "{\"p\":%.9f}", mcnemarExactP(b, c)));
            return;
        }
        System.err.println("usage: EvalStatistics wilson <successes> <trials> | mcnemar <b> <c>");
        System.exit(2);
    }
}
