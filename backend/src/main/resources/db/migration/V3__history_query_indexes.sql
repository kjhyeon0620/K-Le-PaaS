-- History query indexes (#79). PostgreSQL does not index foreign keys automatically.
-- Both history APIs filter by the owner column and page by created_at DESC (NlpController, DeploymentController),
-- so each index serves the filter, the sort, and the count query. Index-only; the previous release is unaffected.
create index idx_command_log_user_id_created_at on command_log (user_id, created_at desc);
create index idx_deployments_repository_id_created_at on deployments (repository_id, created_at desc);
