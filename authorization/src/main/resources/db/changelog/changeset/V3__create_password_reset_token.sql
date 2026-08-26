CREATE TABLE IF NOT EXISTS password_reset_token (
  token_id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_password_reset_email ON password_reset_token (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_password_reset_expires ON password_reset_token (expires_at);
