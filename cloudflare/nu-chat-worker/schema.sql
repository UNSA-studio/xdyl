-- ============================================================
--  NU 私聊中继 · 私钥备份表
--
--  注意：线上 v2 的主表结构（v2_accounts / v2_devices / v2_keys /
--  v2_messages / v2_events 等）不在本文件里 —— 它们早已建好，
--  且由 v2 服务端代码的迁移管理。这里只补私钥备份新增的那张表。
--
--  安全前提：
--    pwd_blob / code_blob 都是客户端用 PBKDF2 派生密钥做
--    AES-256-GCM 加密后的密文。服务端没有密码、没有恢复码，
--    数学上无法还原私钥 —— 端到端加密不被削弱。
--
--    code_hash 只是恢复码的 SHA-256，用于"忘记密码时定位记录"，
--    不是恢复码本身。
-- ============================================================

CREATE TABLE IF NOT EXISTS v2_key_backups (
    subject     TEXT PRIMARY KEY,   -- 账号主体（subject，非 username，改用户名不受影响）
    pwd_blob    TEXT NOT NULL,      -- 用登录密码派生密钥加密的 (私钥+公钥)
    code_blob   TEXT NOT NULL,      -- 用恢复码派生密钥加密的 (私钥+公钥)
    code_hash   TEXT NOT NULL,      -- 恢复码的 sha256 hex，仅用于 lookup
    kdf         TEXT NOT NULL,      -- KDF 参数描述，便于将来升级
    updated_at  INTEGER NOT NULL
);