-- #101 recovery deployments record the previous successful deployment they restored. Nullable; previous releases ignore it.
alter table deployments add column recovered_from_deployment_id bigint;
