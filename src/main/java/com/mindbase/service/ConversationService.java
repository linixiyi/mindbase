package com.mindbase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.exception.CustomException;
import com.mindbase.model.Conversation;
import com.mindbase.model.ConversationSession;
import com.mindbase.model.User;
import com.mindbase.repository.ConversationRepository;
import com.mindbase.repository.ConversationSessionRepository;
import com.mindbase.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * 失败回合标记（pm-001）：不改表结构，复用 answer 字段承载失败态，
     * 格式「[回答失败|错误类别]统一用户话术」；读取端据此还原 status=error + errorCategory。
     * 类别码与 ChatHandler.ChatFailureCategory / 前端 chat-failure 字典一一对应。
     */
    static final String FAILURE_ANSWER_PREFIX = "[回答失败|";
    private static final Pattern FAILURE_ANSWER_PATTERN = Pattern.compile("^\\[回答失败\\|([A-Z_]+)\\]");

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void recordConversation(String username, String question, String answer) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        saveConversation(user, question, answer, null, null);
    }

    @Transactional
    public void recordConversation(Long userId, String question, String answer, String conversationId,
                                   Map<String, Map<String, Object>> referenceMappings) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        saveConversation(user, question, answer, conversationId, referenceMappings);
        updateSessionTitleIfDefault(conversationId, question);
        touchSessionUpdatedAt(conversationId);
    }

    private void saveConversation(User user, String question, String answer, String conversationId,
                                  Map<String, Map<String, Object>> referenceMappings) {
        Conversation conversation = new Conversation();
        conversation.setUser(user);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setConversationId(conversationId);
        conversation.setReferenceMappingsJson(writeReferenceMappings(referenceMappings));

        conversationRepository.save(conversation);
    }

    /**
     * 失败回合落库（pm-001）：用户提问原文 + 失败态回复（answer 带失败标记前缀），
     * 让失败问答刷新后仍在历史中可见；成功回答不走这里。
     */
    @Transactional
    public void recordFailedConversation(Long userId, String question, String conversationId,
                                         String errorCategory, String userFacingMessage) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        saveConversation(user, question, buildFailureAnswer(errorCategory, userFacingMessage), conversationId, null);
        updateSessionTitleIfDefault(conversationId, question);
        touchSessionUpdatedAt(conversationId);
    }

    static String buildFailureAnswer(String errorCategory, String userFacingMessage) {
        String category = errorCategory == null || errorCategory.isBlank() ? "OTHER" : errorCategory;
        return FAILURE_ANSWER_PREFIX + category + "]" + (userFacingMessage == null ? "" : userFacingMessage);
    }

    /** 解出失败回合标记：非失败回答返回 null */
    private static DecodedFailure decodeFailureAnswer(String answer) {
        if (answer == null) {
            return null;
        }
        Matcher matcher = FAILURE_ANSWER_PATTERN.matcher(answer);
        if (!matcher.find()) {
            return null;
        }
        return new DecodedFailure(matcher.group(1), answer.substring(matcher.end()));
    }

    private record DecodedFailure(String errorCategory, String content) {
    }

    // ---- ConversationSession management ----

    public List<Map<String, Object>> getConversationSessions(Long userId) {
        List<ConversationSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ConversationSession session : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.getId());
            item.put("conversationId", session.getConversationId());
            item.put("title", session.getTitle() != null ? session.getTitle() : "新对话");
            item.put("status", session.getStatus().name());
            item.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().format(TIMESTAMP_FORMATTER) : null);
            item.put("updatedAt", session.getUpdatedAt() != null ? session.getUpdatedAt().format(TIMESTAMP_FORMATTER) : null);
            result.add(item);
        }

        return result;
    }

    public Map<String, Object> createConversationSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        String conversationId = UUID.randomUUID().toString();

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle("新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);

        // Update Redis so the backend uses this new conversation for subsequent messages
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("title", "新对话");
        result.put("status", "ACTIVE");
        result.put("createdAt", session.getCreatedAt().format(TIMESTAMP_FORMATTER));
        result.put("updatedAt", session.getUpdatedAt().format(TIMESTAMP_FORMATTER));
        return result;
    }

    public void ensureConversationSession(Long userId, String conversationId, String title) {
        if (sessionRepository.existsByConversationId(conversationId)) {
            return;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    public void validateConversationOwner(Long userId, String conversationId) {
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        if (!userId.equals(session.getUser().getId())) {
            throw new CustomException("无权访问此对话", HttpStatus.FORBIDDEN);
        }
    }

    public void switchCurrentConversation(Long userId, String conversationId) {
        // 与 archive/unarchive/rename/delete/read 同防线：switch 指针同样只能指向自己的会话
        // （否则指针指向他人会话后，WS 无参消息会经 getOrCreateConversationId 直信任指针，污染他人历史）
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        if (!userId.equals(session.getUser().getId())) {
            throw new CustomException("无权访问此对话", HttpStatus.FORBIDDEN);
        }
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));
    }

    public void updateSessionTitle(String conversationId, String title) {
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            if (session.getTitle() == null || "新对话".equals(session.getTitle())) {
                session.setTitle(title);
                sessionRepository.save(session);
            }
        });
    }

    /** 归档会话：仅会话属主可操作（与 rename/delete 同一属主校验口径，P0-2 修复） */
    public void archiveConversationSession(Long userId, String conversationId) {
        validateConversationOwner(userId, conversationId);
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(ConversationSession.SessionStatus.ARCHIVED);
        sessionRepository.save(session);
    }

    /** 取消归档会话：仅会话属主可操作（与 rename/delete 同一属主校验口径，P0-2 修复） */
    public void unarchiveConversationSession(Long userId, String conversationId) {
        validateConversationOwner(userId, conversationId);
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        sessionRepository.save(session);
    }

    /**
     * 会话重命名（pm-016）：仅会话属主可改名；标题去空白、非空，超长截断到 50
     * （与 updateSessionTitleIfDefault 的截断口径一致），改名即持久化。
     */
    @Transactional
    public void renameConversationSession(Long userId, String conversationId, String title) {
        validateConversationOwner(userId, conversationId);
        if (title == null || title.isBlank()) {
            throw new CustomException("会话标题不能为空", HttpStatus.BAD_REQUEST);
        }
        String trimmed = title.trim();
        if (trimmed.length() > 50) {
            trimmed = trimmed.substring(0, 50);
        }
        ConversationSession session = sessionRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setTitle(trimmed);
        sessionRepository.save(session);
    }

    /**
     * 会话删除（pm-016）：级联删除该会话的全部消息行与会话行，仅属主可删（管理员不跨用户删）；
     * 同时清掉 Redis 的短期聊天上下文与会话指针，避免后续消息写进已删会话。
     * 不触碰知识库文档与引用证据数据本身。
     */
    @Transactional
    public void deleteConversationSession(Long userId, String conversationId) {
        validateConversationOwner(userId, conversationId);
        conversationRepository.deleteByConversationId(conversationId);
        sessionRepository.findByConversationId(conversationId).ifPresent(sessionRepository::delete);

        String currentKey = "user:" + userId + ":current_conversation";
        if (conversationId.equals(redisTemplate.opsForValue().get(currentKey))) {
            redisTemplate.delete(currentKey);
        }
        redisTemplate.delete("conversation:" + conversationId);
    }

    private void touchSessionUpdatedAt(String conversationId) {
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    public void updateSessionTitleIfDefault(String conversationId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        String trimmed = title.length() > 50 ? title.substring(0, 50) : title;
        sessionRepository.findByConversationId(conversationId).ifPresent(session -> {
            if ("新对话".equals(session.getTitle())) {
                session.setTitle(trimmed);
                sessionRepository.save(session);
            }
        });
    }

    // ---- Message queries ----

    /** 按 conversationId 读历史消息：仅会话属主可读（与 rename/delete 同一属主校验口径） */
    public List<Map<String, Object>> getMessagesByConversationId(Long userId, String conversationId) {
        validateConversationOwner(userId, conversationId);
        List<Conversation> conversations = conversationRepository.findByConversationIdOrderByTimestampAsc(conversationId);
        return toMessageHistory(conversations, false);
    }

    public List<Conversation> getConversations(String username, LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

        if (user.getRole() == User.Role.ADMIN && "all".equals(username)) {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        } else {
            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(
                        user.getId(), startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(user.getId());
            }
        }
    }

    /**
     * 管理员分页查询所有用户的对话记录（pm-008）。
     * page/size 以「问答回合」（一条 Conversation = 一问一答）为单位，
     * 与旧全量接口的 ASC 时间序保持一致，按 (timestamp, id) 双键稳定排序。
     */
    public Page<Conversation> getAllConversations(String adminUsername, String targetUsername,
                                                  LocalDateTime startDate, LocalDateTime endDate,
                                                  int page, int size) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));

        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Unauthorized access", HttpStatus.FORBIDDEN);
        }

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);

        if (targetUsername != null && !targetUsername.isEmpty()) {
            User targetUser = userRepository.findByUsername(targetUsername)
                    .orElseThrow(() -> new CustomException("Target user not found", HttpStatus.NOT_FOUND));

            if (startDate != null && endDate != null) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAscIdAsc(
                        targetUser.getId(), startDate, endDate, pageable);
            }
            return conversationRepository.findByUserIdOrderByTimestampAscIdAsc(targetUser.getId(), pageable);
        }

        if (startDate != null && endDate != null) {
            return conversationRepository.findByTimestampBetweenOrderByTimestampAscIdAsc(startDate, endDate, pageable);
        }
        return conversationRepository.findAllByOrderByTimestampAscIdAsc(pageable);
    }

    public List<Map<String, Object>> toMessageHistory(List<Conversation> conversations, boolean includeUsername) {
        List<Map<String, Object>> messages = new ArrayList<>();

        conversations.stream()
                .sorted(Comparator
                        .comparing(Conversation::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Conversation::getId))
                .forEach(conversation -> {
                    String timestamp = conversation.getTimestamp() != null
                            ? conversation.getTimestamp().format(TIMESTAMP_FORMATTER)
                            : null;
                    String messageConversationId = conversation.getConversationId() != null
                            ? conversation.getConversationId()
                            : String.valueOf(conversation.getId());
                    // 失败回合（pm-001）：还原 status=error + errorCategory，回复内容去掉标记前缀
                    DecodedFailure failure = decodeFailureAnswer(conversation.getAnswer());

                    messages.add(buildMessage(
                            "user",
                            conversation.getQuestion(),
                            timestamp,
                            messageConversationId,
                            null,
                            includeUsername ? conversation.getUser().getUsername() : null,
                            null,
                            null
                    ));
                    messages.add(buildMessage(
                            "assistant",
                            failure != null ? failure.content() : conversation.getAnswer(),
                            timestamp,
                            messageConversationId,
                            parseReferenceMappings(conversation.getReferenceMappingsJson()),
                            includeUsername ? conversation.getUser().getUsername() : null,
                            failure != null ? "error" : null,
                            failure != null ? failure.errorCategory() : null
                    ));
                });

        return messages;
    }

    private Map<String, Object> buildMessage(String role, String content, String timestamp, String conversationId,
                                             Map<String, Map<String, Object>> referenceMappings, String username,
                                             String status, String errorCategory) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        if (timestamp != null) {
            message.put("timestamp", timestamp);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            message.put("conversationId", conversationId);
        }
        if (referenceMappings != null && !referenceMappings.isEmpty()) {
            message.put("referenceMappings", referenceMappings);
        }
        if (username != null && !username.isBlank()) {
            message.put("username", username);
        }
        if (status != null) {
            message.put("status", status);
        }
        if (errorCategory != null) {
            message.put("errorCategory", errorCategory);
        }
        return message;
    }

    private String writeReferenceMappings(Map<String, Map<String, Object>> referenceMappings) {
        if (referenceMappings == null || referenceMappings.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(referenceMappings);
        } catch (Exception e) {
            logger.warn("序列化引用映射失败，将跳过持久化引用详情", e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> parseReferenceMappings(String referenceMappingsJson) {
        if (referenceMappingsJson == null || referenceMappingsJson.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(referenceMappingsJson, new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("解析引用映射失败，将返回无引用详情的历史记录", e);
            return null;
        }
    }
}
