-- #56 approved deployment config pinning. Nullable; previous releases ignore these columns.
alter table command_log add column approved_config_fingerprint varchar(64);
alter table deployments add column approved_config_fingerprint varchar(64);
