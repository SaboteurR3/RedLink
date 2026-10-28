-- Random events (ticket 19): a dust storm lasts until this time; NULL = clear sky.
ALTER TABLE rover_state ADD COLUMN dust_storm_until TIMESTAMPTZ;
