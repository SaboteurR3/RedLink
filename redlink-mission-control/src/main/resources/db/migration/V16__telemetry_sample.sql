-- DRILL_SAMPLE now reports what it drilled (rock or soil) as its own telemetry type.
ALTER TABLE telemetry DROP CONSTRAINT telemetry_type_ck;
ALTER TABLE telemetry ADD CONSTRAINT telemetry_type_ck
    CHECK (type IN ('COMMAND_STATUS', 'ROVER_STATE', 'EVENT', 'PHOTO', 'SAMPLE'));
