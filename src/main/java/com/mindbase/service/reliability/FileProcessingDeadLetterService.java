package com.mindbase.service.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.exception.CustomException;
import com.mindbase.model.FileProcessingDeadLetter;
import com.mindbase.model.FileProcessingTask;
import com.mindbase.repository.FileProcessingDeadLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 死信任务的查询和人工重放。
 * <p>
 * 重放复用 {@link FileProcessingOutboxService#append}：任务回到 outbox 走既有投递链（事务内发 Kafka，失败指数退避补发），
 * 处理链再次失败会作为新的死信消息重新落库（按 DLT 位置去重，不影响新记录）。重放成功后删除原死信记录，
 * 列表只保留待处理项；没有状态列，避免已重放记录被二次重放。
 */
@Service
public class FileProcessingDeadLetterService {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingDeadLetterService.class);

    private final FileProcessingDeadLetterRepository deadLetterRepository;
    private final FileProcessingOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public FileProcessingDeadLetterService(FileProcessingDeadLetterRepository deadLetterRepository,
                                           FileProcessingOutboxService outboxService,
                                           ObjectMapper objectMapper) {
        this.deadLetterRepository = deadLetterRepository;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> list(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FileProcessingDeadLetter> result = deadLetterRepository.findAll(pageable);
        return Map.of(
                "records", result.getContent(),
                "total", result.getTotalElements(),
                "pages", result.getTotalPages(),
                "current", page,
                "size", size
        );
    }

    /**
     * 人工重放：载荷原样反序列化后经 outbox 重新投递，操作人记入日志，计费仍按原任务字段。
     * outbox 落库与死信删除同一事务提交，删除失败时下次仍可重放，最多产生一次重复处理（消费端幂等）。
     */
    @Transactional
    public FileProcessingDeadLetter replay(Long id, String operator) {
        FileProcessingDeadLetter deadLetter = deadLetterRepository.findById(id)
                .orElseThrow(() -> new CustomException("死信记录不存在: " + id, HttpStatus.NOT_FOUND));
        FileProcessingTask task = parseTask(deadLetter);
        outboxService.append(task);
        deadLetterRepository.delete(deadLetter);
        logger.warn("管理员重放死信任务: id={}, fileMd5={}, taskType={}, operator={}", id, task.getFileMd5(), task.getTaskType(), operator);
        return deadLetter;
    }

    private FileProcessingTask parseTask(FileProcessingDeadLetter deadLetter) {
        if (deadLetter.getPayload() == null || deadLetter.getPayload().isBlank()) {
            throw new CustomException("死信记录没有可重放的载荷: " + deadLetter.getId(), HttpStatus.CONFLICT);
        }
        try {
            FileProcessingTask task = objectMapper.readValue(deadLetter.getPayload(), FileProcessingTask.class);
            if (task.getFileMd5() == null || task.getFileMd5().isBlank()) {
                throw new CustomException("死信载荷缺少 fileMd5，无法重放: " + deadLetter.getId(), HttpStatus.CONFLICT);
            }
            return task;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("死信载荷无法解析为文件处理任务: " + deadLetter.getId(), HttpStatus.CONFLICT);
        }
    }
}
