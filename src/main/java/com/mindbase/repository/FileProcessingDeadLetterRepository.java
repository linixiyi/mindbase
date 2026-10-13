package com.mindbase.repository;

import com.mindbase.model.FileProcessingDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileProcessingDeadLetterRepository extends JpaRepository<FileProcessingDeadLetter, Long> {

    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String sourceTopic, int sourcePartition, long sourceOffset);
}
