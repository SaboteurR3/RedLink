-- Rover 2 (ticket 32) lands at the start cell of the second site (sites/gale-sim/site.properties).
-- Mission control seeds the same rover on its side.
INSERT INTO rover_state (rover_id, name, site_id, x, y, heading, battery_percent, temperature_celsius,
                         storage_used_percent, mode)
VALUES (2, 'Curiosity-sim', 'gale-sim', 20, 40, 'EAST', 100, -25, 0, 'NOMINAL');
