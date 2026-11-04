package com.mindbase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.exception.CustomException;
import com.mindbase.model.Conversation;
import com.mindbase.model.ConversationSession;
import com.mindbase.model.User;
import com.mindbase.repository.ConversationRepository;
import com.mindbase.repository.ConversationSessionRepository;
import com.mindbase.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationSessionRepository sessionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testRecordConversation() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        conversationService.recordConversation("testuser", "What is AI?", "AI stands for Artificial Intelligence.");

        verify(conversationRepository, times(1)).save(any(Conversation.class));
    }

    @Test
    void testGetConversations() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setQuestion("What is AI?");
        conversation.setAnswer("AI stands for Artificial Intelligence.");
        when(conversationRepository.findByUserIdOrderByTimestampAsc(anyLong())).thenReturn(List.of(conversation));

        var result = conversationService.getConversations("testuser", null, null);
        assertEquals(1, result.size());
    }

    // ---- 会话重命名与删除（pm-016）----

    private ConversationSession ownedSession(Long ownerId, String conversationId) {
        User owner = new User();
        owner.setId(ownerId);
        ConversationSession session = new ConversationSession();
        session.setUser(owner);
        session.setConversationId(conversationId);
        session.setTitle("旧标题");
        return session;
    }

    @Test
    void testRenameConversationSessionTrimsAndSaves() {
        ConversationSession session = ownedSession(1L, "c1");
        when(sessionRepository.findByConversationId("c1")).thenReturn(Optional.of(session));

        conversationService.renameConversationSession(1L, "c1", "  新标题  ");

        assertEquals("新标题", session.getTitle());
        verify(sessionRepository, times(1)).save(session);
    }

    @Test
    void testRenameConversationSessionRejectsBlankTitle() {
        ConversationSession session = ownedSession(1L, "c1");
        when(sessionRepository.findByConversationId("c1")).thenReturn(Optional.of(session));

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(CustomException.class,
                () -> conversationService.renameConversationSession(1L, "c1", "   ")).getStatus());
        verify(sessionRepository, never()).save(any(ConversationSession.class));
    }

    @Test
    void testRenameConversationSessionRejectsNonOwner() {
        ConversationSession session = ownedSession(1L, "c1");
        when(sessionRepository.findByConversationId("c1")).thenReturn(Optional.of(session));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> conversationService.renameConversationSession(2L, "c1", "改名")).getStatus());
        verify(sessionRepository, never()).save(any(ConversationSession.class));
    }

    @Test
    void testDeleteConversationSessionCascadesMessagesAndRedis() {
        ConversationSession session = ownedSession(1L, "c1");
        when(sessionRepository.findByConversationId("c1")).thenReturn(Optional.of(session));
        when(valueOperations.get("user:1:current_conversation")).thenReturn("c1");

        conversationService.deleteConversationSession(1L, "c1");

        verify(conversationRepository, times(1)).deleteByConversationId("c1");
        verify(sessionRepository, times(1)).delete(session);
        verify(redisTemplate, times(1)).delete("user:1:current_conversation");
        verify(redisTemplate, times(1)).delete("conversation:c1");
    }

    @Test
    void testDeleteConversationSessionRejectsNonOwner() {
        ConversationSession session = ownedSession(1L, "c1");
        when(sessionRepository.findByConversationId("c1")).thenReturn(Optional.of(session));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> conversationService.deleteConversationSession(2L, "c1")).getStatus());
        verify(conversationRepository, never()).deleteByConversationId(anyString());
        verify(sessionRepository, never()).delete(any(ConversationSession.class));
    }
}