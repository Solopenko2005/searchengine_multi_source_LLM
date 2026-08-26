ALTER TABLE users ADD COLUMN IF NOT EXISTS admin_email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS admin_verification_token_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS admin_verification_expires_at TIMESTAMP WITH TIME ZONE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_admin_verification_token_hash
    ON users (admin_verification_token_hash)
    WHERE admin_verification_token_hash IS NOT NULL;

UPDATE users
SET admin_email_verified = TRUE
WHERE role = 'ADMIN';
