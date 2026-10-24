package com.mindbase.controller;

import com.mindbase.exception.CustomException;
import com.mindbase.model.FileProcessingDeadLetter;
import com.mindbase.service.reliability.FileProcessingDeadLetterService;
import com.mindbase.utils.JwtUtils;
import com.mindbase.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件处理死信管理（仅管理员，/api/v1/admin/** 由 SecurityConfig 限制为 ADMIN 角色）：
 * 查看重试耗尽的死信任务、人工重放回文件处理链。重放不会自动触发，由管理员显式发起。
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
public class FileProcessingDeadLetterAdminController {

    private final FileProcessingDeadLetterService deadLetterService;
    private final JwtUtils jwtUtils;

    public FileProcessingDeadLetterAdminController(FileProcessingDeadLetterService deadLetterService, JwtUtils jwtUtils) {
        this.deadLetterService = deadLetterService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        try {
            return ok("获取死信列表成功", deadLetterService.list(page, size));
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "获取死信列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<?> replay(@RequestHeader("Authorization") String token,
                                    @PathVariable Long id) {
        String operator = operator(token);
        try {
            FileProcessingDeadLetter deadLetter = deadLetterService.replay(id, operator);
            LogUtils.logUserOperation(operator, "DEAD_LETTER_REPLAY", String.valueOf(id),
                    deadLetter.getFileMd5() + " / " + deadLetter.getTaskType());
            return ok("已重新投递，死信记录已清除", deadLetter);
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("DEAD_LETTER_REPLAY", operator, "重放死信任务失败: id=%s", e, id);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "重放死信任务失败: " + e.getMessage());
        }
    }

    private String operator(String token) {
        return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.value(), "message", message));
    }
}
