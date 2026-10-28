#!/bin/sh
# Runs once, on the first start of an empty data volume.
# One database per service, each owned by its own login role, so mission-control can never read
# the rover's tables and vice versa (Earth and Mars share only the contract).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    --set=mc_password="$MISSION_CONTROL_DB_PASSWORD" \
    --set=rover_password="$ROVER_DB_PASSWORD" <<'SQL'
CREATE ROLE mission_control LOGIN PASSWORD :'mc_password';
CREATE DATABASE mission_control OWNER mission_control;
REVOKE ALL ON DATABASE mission_control FROM PUBLIC;

CREATE ROLE rover LOGIN PASSWORD :'rover_password';
CREATE DATABASE rover OWNER rover;
REVOKE ALL ON DATABASE rover FROM PUBLIC;
SQL
