-- Failed logins per (username, client IP) (ticket 34): in the database instead of in memory, so the
-- limit holds across every mission-control instance behind the load balancer.
CREATE TABLE login_failures
(
    username     VARCHAR(64)  NOT NULL,
    client_ip    VARCHAR(64)  NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    failures     INT          NOT NULL,
    PRIMARY KEY (username, client_ip)
);

CREATE INDEX login_failures_window_idx ON login_failures (window_start);
