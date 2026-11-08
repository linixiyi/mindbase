package com.mindbase.repository;

import com.mindbase.model.DocumentVector;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    List<DocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5); // 按分块顺序查询某文件的所有分块

    // 分页读取分块，向量化时一批一批处理，不把整份文件的分块一次性读进内存
    Slice<DocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5, Pageable pageable);

    // 按 chunkId 区间读取相邻分块：上下文检索取文档窗口、父子检索取父块都用它，避免整份文件读进内存
    List<DocumentVector> findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(String fileMd5, Integer fromChunkId, Integer toChunkId);

    long countByFileMd5(String fileMd5);

    long countByFileMd5AndPageNumberIsNotNull(String fileMd5);
    
    /**
     * 删除指定上传者的指定文件的所有文档向量记录（按 (userId, fileMd5) 收口，多租户安全）。
     * 同一 file_md5 可能对应多个上传者（不同用户上传相同字节文件），清理时必须带 user_id，
     * 否则会删掉其他用户的向量。
     *
     * @param userId  上传用户ID
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE user_id = ?1 AND file_md5 = ?2", nativeQuery = true)
    void deleteByUserIdAndFileMd5(String userId, String fileMd5);

    /**
     * @deprecated 无上传者维度，同 MD5 文件在多用户场景下会误删其他用户的向量，
     * 请改用 {@link #deleteByUserIdAndFileMd5(String, String)}。
     * 旧调用点迁移完成前暂保留。
     *
     * @param fileMd5 文件MD5
     */
    @Deprecated
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
