package com.mindbase.repository;

import com.mindbase.model.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 根据用户 ID 和时间范围查询对话记录。
     *
     * @param userId 用户 ID
     * @param startDate 起始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndTimestampBetweenOrderByTimestampAsc(Long userId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 根据用户 ID 查询所有对话记录。
     *
     * @param userId 用户 ID
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdOrderByTimestampAsc(Long userId);

    /**
     * 根据时间范围查询所有对话记录。
     *
     * @param startDate 起始日期
     * @param endDate 结束日期
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime startDate, LocalDateTime endDate);

    @EntityGraph(attributePaths = "user")
    List<Conversation> findAllByOrderByTimestampAsc();

    List<Conversation> findByConversationIdOrderByTimestampAsc(String conversationId);

    // ---- 分页查询（管理端聊天记录，pm-008）：按 (timestamp, id) 双键稳定排序，与 toMessageHistory 的排序一致 ----

    @EntityGraph(attributePaths = "user")
    Page<Conversation> findByUserIdAndTimestampBetweenOrderByTimestampAscIdAsc(Long userId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Conversation> findByUserIdOrderByTimestampAscIdAsc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Conversation> findByTimestampBetweenOrderByTimestampAscIdAsc(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Conversation> findAllByOrderByTimestampAscIdAsc(Pageable pageable);
}
