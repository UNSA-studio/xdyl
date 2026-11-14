-- ============================================================
--  NU 私聊中继 D1 表结构
--
--  已有部署如果早就建过 users / messages / reads，只需执行
--  最后一段 key_backups 的建表语句即可。
-- ============================================================

-- 用户公钥（登记，供对端取用）
CREATE TABLE IF NOT EXISTS users (
    username    TEXT PRIMARY KEY,
    public_key  TEXT NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- 消息（双向同步，seq 自增游标）
CREATE TABLE IF NOT EXISTS messages (
    seq         INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id      TEXT NOT NULL,
    from_user   TEXT NOT NULL,
    to_user     TEXT NOT NULL,
    cipher      TEXT NOT NULL,
    ts          INTEGER NOT NULL,
    created_at  INTEGER NOT NULL,
    UNIQUE (from_user, msg_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_seq ON messages (seq);
CREATE INDEX IF NOT EXISTS idx_messages_to  ON messages (to_user, seq);
CREATE INDEX IF NOT EXISTS idx_messages_from ON messages (from_user, seq);

-- 已读水位
CREATE TABLE IF NOT EXISTS reads (
    reader      TEXT NOT NULL,
    peer        TEXT NOT NULL,
    upto_seq    INTEGER NOT NULL DEFAULT 0,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (reader, peer)
);

-- ============================================================
--  私钥备份（B1 + B3）
--
--  安全前提：这里存的两个 blob 都是客户端用 PBKDF2 派生密钥
--  AES-256-GCM 加密后的密文。服务端没有密码、没有恢复码，
--  在数学上无法还原私钥。
--
--  code_hash 是恢复码的 SHA-256（只用来在"忘记密码"时定位记录），
--  不是恢复码本身，所以即使整表泄露也无法直接解密任何消息。
-- ============================================================
CREATE TABLE IF NOT EXISTS key_backups (
    username    TEXT PRIMARY KEY,
    pwd_blob    TEXT NOT NULL,     -- 用登录密码派生密钥加密
    code_blob   TEXT NOT NULL,     -- 用恢复码派生密钥加密
    code_hash   TEXT NOT NULL,     -- 恢复码的 sha256 hex，用于 lookup
    kdf         TEXT NOT NULL,     -- KDF 参数描述，便于将来升级
    updated_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_key_backups_code
    ON key_backups (username, code_hash);