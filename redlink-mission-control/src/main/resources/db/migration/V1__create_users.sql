-- Operators who use mission control. Passwords are stored only as delegating-encoder hashes
-- ("{bcrypt}$2a$..."), so the algorithm can be upgraded later without a migration.
CREATE TABLE users
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_username_uk UNIQUE (username),
    -- Usernames are case-insensitive; storing them lower-case keeps the unique constraint honest.
    CONSTRAINT users_username_lowercase_ck CHECK (username = lower(username)),
    CONSTRAINT users_role_ck CHECK (role IN ('VIEWER', 'OPERATOR', 'FLIGHT_DIRECTOR'))
);
