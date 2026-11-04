package com.mindbase.repository;

import com.mindbase.model.OrganizationTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrganizationTagRepository extends JpaRepository<OrganizationTag, String> {
    Optional<OrganizationTag> findByTagId(String tagId);
    List<OrganizationTag> findByParentTag(String parentTag);
    boolean existsByTagId(String tagId);

    /**
     * pm-023：按 tagId 集合一次性批量取标签，替代文件列表逐行 findByTagId 的 N+1 查询
     */
    List<OrganizationTag> findByTagIdIn(Collection<String> tagIds);
} 