-- Public images can be deployed without an image pull secret (#74). An unused, expired credential must not break deployments.
-- Existing rows keep using their pull secret; the previous release ignores the column.
alter table deployment_configs add column image_pull_secret_enabled boolean default true not null;
