package com.mindbase.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(
        name = "document_vectors",
        // 同一用户上传的同一文件同一分块只能有一行：Kafka 重复投递时即使清理遗漏，也会在插入时失败而不是悄悄重复；
        // 键带 user_id，两个用户上传相同字节文件（同 file_md5）时各自的向量互不冲突、互不覆盖
        uniqueConstraints = @UniqueConstraint(name = "uk_document_vectors_user_md5_chunk", columnNames = {"user_id", "file_md5", "chunk_id"})
)
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    /**
     * 上下文检索（Contextual Retrieval）为该分块生成的一两句上下文说明，只参与 embedding 和 BM25，
     * 不作为原文展示或引用。为空表示未开启、尚未生成或生成失败（按原文入索引）。
     */
    @Column(name = "chunk_context", columnDefinition = "TEXT")
    private String chunkContext;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}
