-- W3C traceparent of the request that created the command (ticket 33): the uplink is sent later by
-- a scheduler, which continues this trace instead of starting a new one.
ALTER TABLE commands ADD COLUMN trace_parent VARCHAR(55);
