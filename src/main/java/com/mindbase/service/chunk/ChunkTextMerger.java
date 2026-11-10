package com.mindbase.service.chunk;

import java.util.List;

/**
 * 把同一文件里 chunkIndex 相邻的分块按顺序拼回连续文本。
 * <p>
 * task-0008 起分块正文不再携带重叠（ParseService 只把重叠长度记录到日志元数据，
 * 邻块上下文由 ParentContextExpander 的 neighbour-chunks 窗口提供），所以合并就是邻接拼接；
 * 对历史数据里旧版 {@code 重叠句\n\n} 固定格式的前缀保留一次精确剥离，识别不出就原样拼接。
 * 旧版"12 字相同"的字面启发式已从合并路径移除：旧格式前缀本身有固定的 {@code \n\n} 形状，
 * 无需按长度猜测；该启发式只保留在 {@link #stripLeadingOverlap}（历史渲染路径仍在使用）。
 */
public final class ChunkTextMerger {

    static final String SEPARATOR = "\n\n";
    private static final int MIN_LITERAL_OVERLAP = 12;
    private static final int MAX_LITERAL_PROBE = 600;

    private ChunkTextMerger() {
    }

    /**
     * @param orderedChunks 同一文件按 chunkIndex 升序且邻接的分块文本
     *                      （顺序与邻接由调用方保证，如 ParentContextExpander 的 chunkId 区间查询）
     */
    public static String merge(List<String> orderedChunks) {
        if (orderedChunks == null || orderedChunks.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        String previous = null;
        for (String chunk : orderedChunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String text = chunk.trim();
            String addition = previous == null ? text : stripLegacyOverlapPrefix(previous, text);
            if (!addition.isEmpty()) {
                if (!merged.isEmpty()) {
                    merged.append(SEPARATOR);
                }
                merged.append(addition);
            }
            previous = text;
        }
        return merged.toString();
    }

    /**
     * 历史分块（task-0008 之前解析）开头带 {@code 重叠句\n\n} 前缀：只按这个固定格式精确剥离；
     * 新分块正文不带重叠，这里通常原样返回。不再做"12 字相同"的字面探测。
     */
    static String stripLegacyOverlapPrefix(String previous, String next) {
        int separator = next.indexOf(SEPARATOR);
        if (separator > 0) {
            String head = next.substring(0, separator);
            String compactHead = compact(head);
            if (!compactHead.isEmpty() && compact(previous).endsWith(compactHead)) {
                return next.substring(separator + SEPARATOR.length()).trim();
            }
        }
        return next;
    }

    /**
     * 旧版重叠探测（含"12 字相同"字面启发式），仅保留给历史数据渲染路径（ContextWindowBuilder）使用；
     * 父块合并已改用 {@link #stripLegacyOverlapPrefix}，新分块正文不带重叠时结果与原样拼接一致。
     */
    public static String stripLeadingOverlap(String previous, String next) {
        int separator = next.indexOf(SEPARATOR);
        if (separator > 0) {
            String head = next.substring(0, separator);
            String compactHead = compact(head);
            if (!compactHead.isEmpty() && compact(previous).endsWith(compactHead)) {
                return next.substring(separator + SEPARATOR.length()).trim();
            }
        }
        int maxProbe = Math.min(Math.min(previous.length(), next.length() - 1), MAX_LITERAL_PROBE);
        for (int length = maxProbe; length >= MIN_LITERAL_OVERLAP; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(length).trim();
            }
        }
        return next;
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
