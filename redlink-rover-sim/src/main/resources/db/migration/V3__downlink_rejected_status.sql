-- A packet Earth refused for good (400/409/413/422) is kept for inspection but never resent.
ALTER TABLE downlink_packets DROP CONSTRAINT downlink_packets_status_ck;
ALTER TABLE downlink_packets ADD CONSTRAINT downlink_packets_status_ck CHECK (status IN ('PENDING', 'SENT', 'REJECTED'));
