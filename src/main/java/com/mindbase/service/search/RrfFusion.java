package com.mindbase.service.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）。
 * 只看文档在每一路结果里的名次，不看原始分数：BM25 分数和向量相似度量纲不同，直接加权并不可靠，
 * 但名次贡献可以按路加权（score += weight / (k + rank)）。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * 等权融合（V6 语义保留）：每一路权重都是 1.0，内部委托 {@link #fuse(double[], int, Function, List[])}。
     *
     * @param k           平滑常数，常用 60
     * @param keyFn       文档去重键（同一文档在两路结果中出现时合并）
     * @param rankedLists 各路召回结果，每一路已按相关性降序排列
     * @return 按 RRF 分数降序排列的融合结果；分数相同按首次出现顺序
     */
    @SafeVarargs
    public static <T> List<Fused<T>> fuse(int k, Function<T, String> keyFn, List<T>... rankedLists) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k 必须为正数");
        }
        if (rankedLists.length == 0) {
            return List.of();
        }
        double[] weights = new double[rankedLists.length];
        Arrays.fill(weights, 1.0d);
        return fuse(weights, k, keyFn, rankedLists);
    }

    /**
     * 按路加权的 RRF 融合：第 i 路名次 rank 的贡献为 weights[i] / (k + rank)。
     * k 语义、同分稳定排序（按首次出现顺序）与去重规则（同一路只取最靠前名次）均与等权版本一致。
     *
     * @param weights 每一路的权重，长度必须等于路数，每项 >= 0 且有限，总和 > 0
     * @param k       平滑常数，常用 60
     * @param keyFn   文档去重键（同一文档在各路结果中出现时合并）
     * @param lists   各路召回结果，每一路已按相关性降序排列
     * @return 按 RRF 分数降序排列的融合结果；分数相同按首次出现顺序
     * @throws IllegalArgumentException 权重数量与路数不符、存在负数或非有限权重、或权重总和为 0 时
     */
    @SafeVarargs
    public static <T> List<Fused<T>> fuse(double[] weights, int k, Function<T, String> keyFn, List<T>... lists) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k 必须为正数");
        }
        if (weights == null || lists == null || weights.length != lists.length) {
            throw new IllegalArgumentException("权重数量必须与参与融合的路数一致: weights="
                    + (weights == null ? 0 : weights.length) + ", lists=" + (lists == null ? 0 : lists.length));
        }
        double weightSum = 0.0d;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight < 0.0d) {
                throw new IllegalArgumentException("每路权重必须为非负有限数，收到: " + weight);
            }
            weightSum += weight;
        }
        if (weightSum <= 0.0d) {
            throw new IllegalArgumentException("权重之和必须大于 0");
        }

        Map<String, Accumulator<T>> accumulators = new LinkedHashMap<>();
        for (int listIndex = 0; listIndex < lists.length; listIndex++) {
            List<T> list = lists[listIndex];
            if (list == null) {
                continue;
            }
            for (int position = 0; position < list.size(); position++) {
                T item = list.get(position);
                String key = keyFn.apply(item);
                if (key == null) {
                    continue;
                }
                Accumulator<T> acc = accumulators.computeIfAbsent(key,
                        ignored -> new Accumulator<>(item, lists.length));
                // 同一路里重复出现时只取最靠前的名次
                if (acc.ranks[listIndex] == 0) {
                    int rank = position + 1;
                    acc.ranks[listIndex] = rank;
                    acc.score += weights[listIndex] / (k + rank);
                }
            }
        }

        List<Fused<T>> fused = new ArrayList<>(accumulators.size());
        accumulators.forEach((key, acc) -> fused.add(new Fused<>(key, acc.item, acc.score, acc.ranks.clone())));
        // List.sort 是稳定排序，分数相同保持首次出现顺序
        fused.sort(Comparator.comparingDouble((Fused<T> f) -> f.score()).reversed());
        return List.copyOf(fused);
    }

    /**
     * @param ranks 在每一路中的名次（从 1 开始），0 表示该路未召回
     */
    public record Fused<T>(String key, T item, double score, int[] ranks) {
        public boolean recalledBy(int listIndex) {
            return listIndex < ranks.length && ranks[listIndex] > 0;
        }
    }

    private static final class Accumulator<T> {
        private final T item;
        private final int[] ranks;
        private double score;

        private Accumulator(T item, int listCount) {
            this.item = item;
            this.ranks = new int[listCount];
        }
    }
}
