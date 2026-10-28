-- Rover 2 (ticket 32), as planned at landing (site gale-sim, start cell 20,40). rover-sim seeds the
-- same rover on its side (its own V5); the two databases share no tables, only the id.
INSERT INTO rovers (id, name, site_id, last_x, last_y, last_heading, last_battery_percent,
                    last_temperature_celsius, last_storage_used_percent, last_mode)
VALUES (2, 'Curiosity-sim', 'gale-sim', 20, 40, 'EAST', 100, -25, 0, 'NOMINAL');

INSERT INTO uplink_sequences (rover_id)
VALUES (2);
