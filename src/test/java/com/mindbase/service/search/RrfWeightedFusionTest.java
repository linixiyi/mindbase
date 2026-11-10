package com.mindbase.service.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0014：RRF 分路权重。等权结果必须与旧 fuse(int k, ...) 逐条一致；权重只缩放名次贡献，
 * 不改变 k 语义、同分稳定排序与同路去重规则。
 */
class RrfWeightedFusionTest {

    private static final Function<String, String> KEY = Function.identity();

    @Test
    void equalWeightsReproduceLegacyFuseExactly() {
        List<String> bm25 = List.of("a", "b", "c");
        List<String> knn = List.of("c", "d", "a");

        List<RrfFusion.Fused<String>> legacy = RrfFusion.fuse(60, KEY, bm25, knn);
        List<RrfFusion.Fused<String>> weighted =
                RrfFusion.fuse(new double[]{1.0d, 1.0d}, 60, KEY, bm25, knn);

        assertEquals(legacy.size(), weighted.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i).key(), weighted.get(i).key(), "第 " + i + " 位文档不一致");
            assertEquals(legacy.get(i).score(), weighted.get(i).score(), 0.0d, "第 " + i + " 位分数不一致");
            assertArrayEquals(legacy.get(i).ranks(), weighted.get(i).ranks(), "第 " + i + " 位名次不一致");
        }
        // 顺手核对与旧测试一致的排序：a 双路命中排第一
        assertEquals(List.of("a", "c", "b", "d"), weighted.stream().map(RrfFusion.Fused::key).toList());
        assertEquals(1.0 / 61 + 1.0 / 63, weighted.get(0).score(), 1e-12);
    }

    @Test
    void zeroWeightPathContributesNothing() {
        List<String> bm25 = List.of("x", "y");
        List<String> knn = List.of("y", "z");

        List<RrfFusion.Fused<String>> fused =
                RrfFusion.fuse(new double[]{0.0d, 1.0d}, 60, KEY, bm25, knn);

        assertEquals(List.of("y", "z", "x"), fused.stream().map(RrfFusion.Fused::key).toList());
        // x 只被权重 0 的 BM25 路召回：名次仍记录，但分数贡献为 0
        RrfFusion.Fused<String> x = fused.get(2);
        assertEquals(0.0d, x.score(), 0.0d);
        assertArrayEquals(new int[]{1, 0}, x.ranks());
        assertTrue(x.recalledBy(0));
        // y 的分数只来自 KNN 路：BM25 rank 2 的贡献被权重 0 抹掉
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 62, fused.get(1).score(), 1e-12);
    }

    @Test
    void doubleWeightFlipsConstructedOrdering() {
        // k=60 构造：等权时 A 险胜 B（125/3904 > 125/3906），
        // BM25 路加权 2.0 后 B 反超（94/1953 > 93/1952），顺序翻转
        List<String> bm25 = List.of("f1", "B", "f2", "A");
        List<String> knn = List.of("A", "f3", "B");

        List<RrfFusion.Fused<String>> equal =
                RrfFusion.fuse(new double[]{1.0d, 1.0d}, 60, KEY, bm25, knn);
        assertEquals(List.of("A", "B", "f1", "f3", "f2"), equal.stream().map(RrfFusion.Fused::key).toList());
        assertEquals(1.0 / 64 + 1.0 / 61, equal.get(0).score(), 1e-12, "A = bm25 rank4 + knn rank1");
        assertEquals(1.0 / 62 + 1.0 / 63, equal.get(1).score(), 1e-12, "B = bm25 rank2 + knn rank3");

        List<RrfFusion.Fused<String>> weighted =
                RrfFusion.fuse(new double[]{2.0d, 1.0d}, 60, KEY, bm25, knn);
        assertEquals(List.of("B", "A", "f1", "f2", "f3"), weighted.stream().map(RrfFusion.Fused::key).toList(),
                "BM25 权重 2.0 后 B 反超 A");
        assertEquals(2.0 / 62 + 1.0 / 63, weighted.get(0).score(), 1e-12);
        assertEquals(2.0 / 64 + 1.0 / 61, weighted.get(1).score(), 1e-12);
    }

    @Test
    void rejectsWeightCountMismatch() {
        List<String> bm25 = List.of("a");
        List<String> knn = List.of("b");

        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{1.0d}, 60, KEY, bm25, knn), "权重少于路数");
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{1.0d, 1.0d, 1.0d}, 60, KEY, bm25, knn), "权重多于路数");
    }

    @Test
    void rejectsNegativeNonFiniteAndZeroSumWeights() {
        List<String> bm25 = List.of("a");
        List<String> knn = List.of("b");

        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{-1.0d, 1.0d}, 60, KEY, bm25, knn), "负权重");
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{Double.NaN, 1.0d}, 60, KEY, bm25, knn), "NaN 非有限");
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{Double.POSITIVE_INFINITY, 1.0d}, 60, KEY, bm25, knn), "无穷大非有限");
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{0.0d, 0.0d}, 60, KEY, bm25, knn), "权重之和必须大于 0");
    }

    @Test
    void weightedFuseKeepsKAndStableTieSemantics() {
        // k 变化仍生效：k=1 时第一名权重差距更大，顺序不变但分数不同
        List<String> bm25 = List.of("a", "b");
        List<String> knn = List.of("b");

        List<RrfFusion.Fused<String>> fused =
                RrfFusion.fuse(new double[]{1.0d, 1.0d}, 1, KEY, bm25, knn);
        assertEquals(List.of("b", "a"), fused.stream().map(RrfFusion.Fused::key).toList());
        assertEquals(1.0 / 2 + 1.0 / 3, fused.get(0).score(), 1e-12, "b：bm25 rank2 + knn rank1，k=1");
        assertEquals(1.0 / 2, fused.get(1).score(), 1e-12, "a：k=1，bm25 rank1");
        // null 路与空路照旧跳过
        assertTrue(RrfFusion.fuse(new double[]{1.0d, 1.0d}, 60, KEY, List.<String>of(), null).isEmpty());
        // 非正 k 照旧拒绝
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(new double[]{1.0d, 1.0d}, 0, KEY, bm25, knn));
    }
}
