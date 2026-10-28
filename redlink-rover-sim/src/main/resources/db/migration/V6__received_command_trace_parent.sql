-- W3C traceparent from the command packet (ticket 33): executing the command later, and reporting
-- on it, continue Earth's trace.
ALTER TABLE received_commands ADD COLUMN trace_parent VARCHAR(55);
