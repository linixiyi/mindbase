package com.mindbase.model;

import com.mindbase.entity.SearchResult;

import java.util.List;

/**
 * 带失败分类的检索结果（task_0005）：results + outcome + detail。
 * detail 面向日志与可观测性（失败原因、降级说明），不直接拼进模型提示词。
 */
public record SearchResponse(List<SearchResult> results, SearchOutcome outcome, String detail) {

    public SearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
        outcome = outcome == null ? SearchOutcome.HIT : outcome;
    }

    public static SearchResponse hit(List<SearchResult> results) {
        return new SearchResponse(results, SearchOutcome.HIT, null);
    }

    public static SearchResponse hit(List<SearchResult> results, String detail) {
        return new SearchResponse(results, SearchOutcome.HIT, detail);
    }

    public static SearchResponse noPermissionMatch(String detail) {
        return new SearchResponse(List.of(), SearchOutcome.NO_PERMISSION_MATCH, detail);
    }

    public static SearchResponse recallFailed(String detail) {
        return new SearchResponse(List.of(), SearchOutcome.RECALL_FAILED, detail);
    }

    public static SearchResponse belowThreshold(List<SearchResult> results, String detail) {
        return new SearchResponse(results, SearchOutcome.BELOW_THRESHOLD, detail);
    }
}
