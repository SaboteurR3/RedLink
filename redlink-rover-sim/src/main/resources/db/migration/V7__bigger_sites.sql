-- Ticket 39: both sites grew to 128 x 128 m and were generated anew. Each rover moves to its site's
-- new start cell (sites/<id>/site.properties); mission control updates what Earth knows (its V17).
UPDATE rover_state SET x = 64, y = 64, heading = 'NORTH' WHERE site_id = 'jezero-sim';
UPDATE rover_state SET x = 40, y = 80, heading = 'NORTH' WHERE site_id = 'gale-sim';
