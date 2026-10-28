-- Creates the RedLink databases on a locally installed PostgreSQL (without Docker).
-- Same result as docker/postgres/init/01-create-databases.sh. Passwords match application-local.yml.
-- Run as a superuser:  psql -U postgres -f scripts/local-db-setup.sql
\set ON_ERROR_STOP on

CREATE ROLE mission_control LOGIN PASSWORD 'mission_control_dev';
CREATE DATABASE mission_control OWNER mission_control;
REVOKE ALL ON DATABASE mission_control FROM PUBLIC;

CREATE ROLE rover LOGIN PASSWORD 'rover_dev';
CREATE DATABASE rover OWNER rover;
REVOKE ALL ON DATABASE rover FROM PUBLIC;

\l mission_control
\l rover
