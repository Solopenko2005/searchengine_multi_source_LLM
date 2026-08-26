CREATE TABLE users (
 id UUID PRIMARY KEY,
 email VARCHAR(255),
 password VARCHAR(255),
 account_id UUID,
 isBlocked BOOLEAN
)