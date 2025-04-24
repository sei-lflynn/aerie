drop table hasura.migrate_plan_to_model_return_value;
drop function hasura.migrate_plan_to_model();

drop table hasura.check_model_compatability_return_value;
drop function hasura.check_model_compatability;

alter table merlin.plan_snapshot
  drop column model_id;

call migrations.mark_migration_rolled_back('18');
