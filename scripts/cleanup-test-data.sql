-- ============================================================================
-- cleanup-test-data.sql —— MindBase 测试数据清理（MySQL：PaiSmart 库）
-- ============================================================================
-- 用途：
--   清理历轮验证（pm-hardening / g 硬化轮 / h5 注册体验轮 / h4 管理可见性 /
--   P0 安全修复轮 / P1a 安全尾巴轮等）在数据库里留下的自动化测试痕迹：
--   测试账号、对应测试邀请码、这些账号的会话与消息、其上传/向量化链路残留、
--   以及注册时自动创建的 PRIVATE_ 前缀私人组织标签。
--
-- 覆盖的测试用户（历史链 + 本轮）：
--   h5%（h5rvw_user1..5、h5debug_reg_1106、h5pm032_1106a..h、h5pm032_final）
--   rv_reg%（rv_reg_mw3tinc4）
--   以及精确名单：e2e-pm-user、g2-disable-probe、s-switch-probe、
--                g-accept-probe、g6probe、audit-sec-probe
--
-- 明确保留（演示数据，删除条件设计上不可触及）：
--   - admin 用户及其全部会话 / 消息 / 文档 / Token 记录
--   - admin 与 default 两个真实组织标签
--   - 未列入精确名单的邀请码（admin 后续真实创建的码不受影响）
--   - document_conflicts 仅删除「新旧两个文件都已不存在于 file_upload」的孤儿
--     记录（历史 h3smoke 冒烟测试残留）；凡文件仍存在的冲突记录一律保留
--
-- 风险与语义：
--   !! 本文件直接执行会立即删数据（mysql PaiSmart < cleanup-test-data.sql）。
--   !! 正确用法是走配套包装脚本：scripts/cleanup-test-data.sh
--        默认 dry-run：只打印每张表将删除的行数，不做任何写入；
--        传入 --yes 才真正执行（显式确认语义，无交互提示）。
--   幂等：所有 DELETE 都带精确 WHERE 前缀/名单条件，重复执行第二遍为 0 行。
--   范围：仅 MySQL。Redis 键（限流桶/Token 缓存）自带 TTL 过期；ES 残留由
--   知识索引重建收口，均不在本脚本范围。
-- ============================================================================

-- [01/14] 测试用户上传文件的 Kafka outbox / 死信残留（按其 file_md5 关联，必须在删除 file_upload 之前）
DELETE FROM file_processing_outbox WHERE file_md5 IN (SELECT file_md5 FROM file_upload WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe'))));
DELETE FROM file_processing_dead_letter WHERE file_md5 IN (SELECT file_md5 FROM file_upload WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe'))));

-- [03/14] 孤儿冲突记录：新旧两个文件都已被删除（历史 h3smoke 冒烟残留），无法再裁决
DELETE FROM document_conflicts WHERE NOT EXISTS (SELECT 1 FROM file_upload f WHERE f.file_md5 = document_conflicts.existing_file_md5) AND NOT EXISTS (SELECT 1 FROM file_upload f2 WHERE f2.file_md5 = document_conflicts.new_file_md5);

-- [04/14] 测试用户名下数据（FK 指向 users，先删子表）
DELETE FROM chunk_info WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM document_vectors WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM conversations WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM conversation_sessions WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM recharge_orders WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM user_daily_chat_count WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM user_token_record WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));
DELETE FROM file_upload WHERE user_id IN (SELECT id FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe')));

-- [12/14] 测试邀请码（精确名单 + H5RVW- 前缀；admin 新建的真实邀请码不受影响）
DELETE FROM invite_codes WHERE code IN ('E2E-PM-2026','G6-REPRO-2026','S-P0-PROBE-INVITE-2026','NFN63MJDZ6JJ9MHF') OR code LIKE 'H5RVW-%';

-- [13/14] 注册测试账号时自动创建的私人组织标签（前缀在 tag_id 列；admin / default 两个真实标签不匹配此前缀）
DELETE FROM organization_tags WHERE tag_id LIKE 'PRIVATE\_%';

-- [14/14] 测试用户本体（admin 用户名 + 非名单用户不受影响；放最后以满足 FK）
DELETE FROM users WHERE username <> 'admin' AND (username LIKE 'h5%' OR username LIKE 'rv_reg%' OR username IN ('e2e-pm-user','g2-disable-probe','s-switch-probe','g-accept-probe','g6probe','audit-sec-probe'));
