-- Per-app health probe and CPU/memory requests/limits (#51).
-- All nullable: existing apps keep running without probes or resource settings, and the previous release ignores these columns.
alter table deployment_configs add column health_probe_path varchar(255);
alter table deployment_configs add column health_probe_port integer;
alter table deployment_configs add column health_probe_initial_delay_seconds integer;
alter table deployment_configs add column health_probe_period_seconds integer;
alter table deployment_configs add column health_probe_failure_threshold integer;
alter table deployment_configs add column health_probe_startup_failure_threshold integer;
alter table deployment_configs add column cpu_request varchar(20);
alter table deployment_configs add column cpu_limit varchar(20);
alter table deployment_configs add column memory_request varchar(20);
alter table deployment_configs add column memory_limit varchar(20);
