-- GitHub webhook delivery that created the deployment (#52). Redelivered pushes must not deploy again.
-- Nullable: API and NLP deployments have none, and the previous release ignores the column. H2 unique allows multiple nulls.
alter table deployments add column github_delivery_id varchar(64);
alter table deployments add constraint uk_deployments_github_delivery_id unique (github_delivery_id);
