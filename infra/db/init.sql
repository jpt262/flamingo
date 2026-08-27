-- Runs once on first Postgres volume initialization.
-- flamingo = dev/application database; flamingo_test = isolated DB for the
-- migration-convergence + persistence contract suites (never share state).
CREATE DATABASE flamingo_test;
