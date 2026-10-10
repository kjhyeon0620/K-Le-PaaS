-- Per-request deployment record (#95): how the request arrived, what was applied, what ran, and how it failed.
-- All columns are nullable. Existing rows stay null and are shown as "not recorded"; nothing is backfilled.
-- The previous release does not read or write these columns.
alter table deployments add column trigger_source varchar(32);
alter table deployments add column requested_by_user_id bigint;
alter table deployments add column command_log_id bigint;
-- Deployment config as applied. Env values are not stored, only names and keyed fingerprints.
alter table deployments add column config_snapshot text;
alter table deployments add column image_digest varchar(255);
alter table deployments add column failure_kind varchar(32);
