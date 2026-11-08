-- P0 隔离修复（task_0001）：document_vectors 补上传者维度并更换唯一键。
-- 背景：旧唯一键 uk_document_vectors_md5_chunk (file_md5, chunk_id) 不含 user_id，
--       两个用户上传相同字节文件（同 file_md5）时互相冲突/覆盖；本脚本把唯一键改为
--       uk_document_vectors_user_md5_chunk (user_id, file_md5, chunk_id)。
-- 适用于已有数据的 MySQL 库（先备份再执行）。新库直接使用 docs/databases/ddl.sql 中的最新定义。
-- 执行顺序：先跑【第四段】只读核对 SQL——核对 1 无输出才继续 1→2→3；核对 2/3 的结果决定第 2 段回填是否需要人工介入。
-- 注意：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS / ADD INDEX IF NOT EXISTS，各段按下列说明跳过已生效步骤。

-- ============ 第一段：加列 ============
-- 本仓库 ddl.sql 与实体（DocumentVector.userId）自初始版本即含 user_id，正常部署的库已存在该列；
-- 仅当 information_schema 确认缺列时执行本段（已存在时执行会报 Duplicate column，跳过即可）：
-- SELECT COUNT(*) FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_vectors' AND COLUMN_NAME = 'user_id';
-- ALTER TABLE document_vectors
--     ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '上传用户ID（P0 隔离维度，回填前的兼容默认值）';

-- ============ 第二段：回填 ============
-- 把 user_id 为空/空串的历史行按 file_upload（(file_md5, user_id) 唯一）回填上传者。
-- 同一 file_md5 可能被多个用户上传过，无法确定归属的行不回填，留给【第四段 核对 3】人工裁决；
-- 为保证 UPDATE JOIN 结果确定，只回填在 file_upload 中只对应一个上传者的 file_md5：
UPDATE document_vectors dv
JOIN (
    SELECT file_md5, MIN(user_id) AS only_user_id
    FROM file_upload
    GROUP BY file_md5
    HAVING COUNT(DISTINCT user_id) = 1
) fu ON fu.file_md5 = dv.file_md5
SET dv.user_id = fu.only_user_id
WHERE dv.user_id IS NULL OR dv.user_id = '';

-- ============ 第三段：换唯一键 ============
-- 前置条件：【第四段 核对 1】无输出（新键下无重复）。旧键比新键严格（新键多一列），换键本身不会新引入冲突。
ALTER TABLE document_vectors
    DROP INDEX uk_document_vectors_md5_chunk,
    ADD UNIQUE KEY uk_document_vectors_user_md5_chunk (user_id, file_md5, chunk_id);

-- ============ 第四段：只读核对 SQL ============
-- 核对 1（换键前置检查）：新键 (user_id, file_md5, chunk_id) 下是否存在重复行。要求：无输出。
SELECT user_id, file_md5, chunk_id, COUNT(*) AS cnt
FROM document_vectors
GROUP BY user_id, file_md5, chunk_id
HAVING cnt > 1;

-- 核对 2（隔离现状盘点，A2 验证）：同 file_md5 已被几个用户写入向量。预期：多用户同 MD5 的环境有输出，
-- 这正是新键要支持的共存形态，不是错误；换键后这些行可安全共存。
SELECT file_md5, chunk_id, COUNT(DISTINCT user_id) AS user_cnt
FROM document_vectors
GROUP BY file_md5, chunk_id
HAVING user_cnt > 1;

-- 核对 3（回填残留）：user_id 仍为空的行。这些行的 file_md5 对应多个上传者，归属不明，
-- 需结合 file_upload 记录人工裁决后再换键；无输出则可直接执行第 3 段。
SELECT dv.vector_id, dv.file_md5, dv.chunk_id, dv.user_id
FROM document_vectors dv
WHERE dv.user_id IS NULL OR dv.user_id = '';
