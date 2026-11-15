-- Device registry: which platform each account device uses (first-launch handshake)
CREATE TABLE IF NOT EXISTS device_registry (
  subject     TEXT NOT NULL,
  device_code TEXT NOT NULL,
  platform    TEXT NOT NULL,
  app_version TEXT,
  first_seen  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL,
  PRIMARY KEY (subject, device_code)
);

-- Private key backup ciphertexts (B1 password-derived + B3 recovery-code).
-- Client-side PBKDF2/AES-GCM; server stores ciphertext only, cannot decrypt.
CREATE TABLE IF NOT EXISTS v2_key_backups (
  subject     TEXT PRIMARY KEY,
  pwd_blob    TEXT NOT NULL,
  code_blob   TEXT NOT NULL,
  code_hash   TEXT NOT NULL,
  kdf         TEXT NOT NULL,
  updated_at  INTEGER NOT NULL
);
