package com.mindbase.repository;

import com.mindbase.model.DocumentVector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量表上传者维度唯一键回归（task_0001）：
 * 唯一键 uk_document_vectors_user_md5_chunk (user_id, file_md5, chunk_id)。
 * 在 H2（MySQL 模式，ddl-auto=create-drop）上按实体生成 schema，验证 JPA 实体约束本身；
 * 迁移脚本由 MySQL 人工执行，H2 不执行迁移脚本，也不为迁就 H2 改生产 SQL。
 * 内联属性强制 H2 内存库：本机测试会激活 dev profile 连真实 MySQL（读的是迁移前的旧唯一键），
 * 必须与开发库隔离。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenantkey;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DocumentVectorTenantKeyTest {

    private static final String FILE_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Test
    void sameFileMd5AndChunkIdWithDifferentUsersCoexists() {
        save("user-a", 0);
        save("user-b", 0);

        // 两个用户上传相同字节文件（同 file_md5 + chunk_id），各自向量共存、互不覆盖
        List<DocumentVector> rows = documentVectorRepository.findByFileMd5(FILE_MD5);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DocumentVector::getUserId)
                .containsExactlyInAnyOrder("user-a", "user-b");
        assertThat(rows).extracting(DocumentVector::getChunkId)
                .containsOnly(0);
    }

    @Test
    void sameUserDuplicateFileMd5AndChunkIdIsRejected() {
        save("user-a", 0);

        // 同一 user_id 重复插入同一 (file_md5, chunk_id)：Kafka 重复投递等场景在插入时失败而不是悄悄重复
        assertThatThrownBy(() -> save("user-a", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteByUserIdAndFileMd5OnlyTouchesThatUsersRows() {
        save("user-a", 0);
        save("user-a", 1);
        save("user-b", 0);
        save("user-b", 1);

        documentVectorRepository.deleteByUserIdAndFileMd5("user-b", FILE_MD5);
        documentVectorRepository.flush();

        // B 的清理不触碰 A 的记录
        List<DocumentVector> remaining = documentVectorRepository.findByFileMd5(FILE_MD5);
        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(DocumentVector::getUserId)
                .containsOnly("user-a");
    }

    private DocumentVector save(String userId, int chunkId) {
        DocumentVector vector = new DocumentVector();
        vector.setUserId(userId);
        vector.setFileMd5(FILE_MD5);
        vector.setChunkId(chunkId);
        vector.setTextContent("chunk-" + userId + "-" + chunkId);
        return documentVectorRepository.saveAndFlush(vector);
    }
}
