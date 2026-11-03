-- pm-012 债务收口（G2）：users 表新增 enabled 列，作为用户禁用态的数据库真相源。
-- 背景：此前禁用态仅存 Redis 持久键 user:status:disabled:{userId}（无 TTL），
--       Redis 数据丢失或清键会使禁用态失效；升级后登录与 token 签发一律读 users.enabled 列。
-- 适用于已有数据的 MySQL 库（先备份再执行）。新库请直接使用 docs/databases/ddl.sql 中的
-- users 建表语句；若未执行本脚本，ddl-auto=update 也会按实体补列（BOOLEAN DEFAULT 1）。
ALTER TABLE users
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用，0 禁用（pm-012 禁用态真相源）';

-- 升级后旧的 Redis 禁用键不再被读取，可选择性清理（键可能本就不存在）：
--   redis-cli --scan --pattern 'user:status:disabled:*' | xargs -r redis-cli del
