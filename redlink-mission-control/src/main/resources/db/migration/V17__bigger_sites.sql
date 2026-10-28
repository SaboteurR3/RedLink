-- Ticket 39: both sites grew to 128 x 128 m and were generated anew, so a position on the old map
-- means nothing on the new one. Earth's last knowledge of each rover moves to its site's start cell
-- (as rover-sim's own V7 moves the rovers themselves). Older telemetry, photos and samples keep the
-- coordinates they had: they are history of the old map.
UPDATE rovers SET last_x = 64, last_y = 64, last_heading = 'NORTH' WHERE site_id = 'jezero-sim';
UPDATE rovers SET last_x = 40, last_y = 80, last_heading = 'NORTH' WHERE site_id = 'gale-sim';
