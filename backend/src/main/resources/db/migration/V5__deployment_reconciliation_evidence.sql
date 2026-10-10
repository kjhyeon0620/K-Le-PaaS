-- Prepare #57 reconciliation; this release reads UNKNOWN but never writes it.
alter table deployments add column applied_resource_uid varchar(255);
alter table deployments add column applied_generation bigint;

alter table deployments drop constraint deployments_status_check;
alter table deployments add constraint deployments_status_check
    check (status in ('BUILDING','CANCELED','DEPLOYING','FAILED','PENDING','SUCCESS','UPLOADING_SOURCE','UNKNOWN'));
