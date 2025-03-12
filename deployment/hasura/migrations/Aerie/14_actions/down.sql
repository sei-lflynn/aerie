drop trigger notify_action_run_inserted on actions.action_run;
drop function actions.notify_action_run_inserted;

drop table actions.action_run;

drop trigger notify_action_definition_inserted on actions.action_definition;
drop function actions.notify_action_definition_inserted;
drop trigger set_timestamp on actions.action_definition;
drop table actions.action_definition;

drop type actions.action_run_status;

drop schema actions;

call migrations.mark_migration_rolled_back('14');
