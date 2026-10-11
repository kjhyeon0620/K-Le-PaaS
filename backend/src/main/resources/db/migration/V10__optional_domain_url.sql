-- #107 NodePort-only apps may have no domain (no Ingress). NULL keeps the unique constraint usable for many apps.
-- The previous release reads NULL as "no Ingress" and keeps working; it only rejects clearing the domain on update.
alter table deployment_configs alter column domain_url drop not null;
