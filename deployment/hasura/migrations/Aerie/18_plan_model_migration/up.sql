alter table merlin.plan_snapshot
add column model_id integer references merlin.mission_model on delete set null default null;

-- Backfill the model_id column for existing snapshots with the current model_id for the snapshot's plan
update merlin.plan_snapshot snap
set model_id = plan.model_id
from merlin.plan
where plan.id = snap.plan_id;


-- Add plan migration functions
create table hasura.migrate_plan_to_model_return_value(result text);
/*
* This function does the following:
*     * creates a snapshot of the specified plan
*     * updates the specified plan to have model_id = _new_model_id
*     * invalidates the activity validations, which will trigger the activity validator to run again
* It will not update the plan if:
*     * user has incorrect permissions (see default_user_roles for details)
*     * there are open merge requests for the given plan
*     * the given plan or model does not exist
*/
create function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json)
  returns hasura.migrate_plan_to_model_return_value
  volatile
  language plpgsql as $$
declare
  _requester_username  text;
  _function_permission permissions.permission;
  _old_model_id        integer;
begin
  _requester_username := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('migrate_plan_to_model', hasura_session);
  perform permissions.raise_if_plan_merge_permission('migrate_plan_to_model', _function_permission);
  if not _function_permission = 'NO_CHECK' then
    call permissions.check_general_permissions('migrate_plan_to_model', _function_permission, _plan_id,
                                               _requester_username);
  end if;


  -- Check for open merge requests
  if exists(select
            from merlin.merge_request mr
            where mr.plan_id_receiving_changes = _plan_id
              and status in ('pending', 'in-progress')) then
    raise exception 'Cannot migrate plan %: it has open merge requests.', _plan_id;
  end if;

  -- Get the old model ID associated with the plan
  select model_id into _old_model_id from merlin.plan where id = _plan_id;

  -- Create snapshot before migration
  perform merlin.create_snapshot(_plan_id,
                                 'Migration from model ' || _old_model_id ||
                                 ' to model ' || _new_model_id ||
                                 ' on ' || NOW(),
                                 'Automatic snapshot before attempting migration from model id ' || _old_model_id ||
                                 ' to model id ' || _new_model_id ||
                                 ' on ' || NOW(),
                                 _requester_username);

  -- Perform model migration
  update merlin.plan
  set model_id = _new_model_id
  where id = _plan_id;

  -- invalidate activity validations to re-run validator
  update merlin.activity_directive_validations
  set status = 'pending'
  where plan_id = _plan_id;

  return row ('success')::hasura.migrate_plan_to_model_return_value;
end
$$;


create table hasura.check_model_compatability_return_value(result json);
/*
* This function checks whether two models are compatible. It returns a json object containing:
*     * removed_activity_types, containing the activity types that are in the old model and not in the new model
*     * altered_activity_types, containing the activity types with dissimilar parameter schemas, and the old and
*            new parameter schemas for this activity type
*/
create function hasura.check_model_compatability(_old_model_id integer, _new_model_id integer, hasura_session json)
  returns hasura.check_model_compatability_return_value
  volatile
  language plpgsql as $$
declare
  _removed_activity_types text;
  _altered_activity_types text;

begin
  _removed_activity_types := coalesce((select json_agg(name)
                                       from merlin.activity_type old_at
                                       where old_at.model_id = _old_model_id
                                         and not exists(select
                                                        from merlin.activity_type new_at
                                                        where new_at.name = old_at.name
                                                          and new_at.model_id = _new_model_id)), '{}'::json);

  _altered_activity_types := coalesce((select json_object_agg(types.n, types.t)
                                       from (select type.name as n,
                                                    json_build_object('old_parameter_schema', old_params,
                                                                      'new_parameter_schema', new_params) as t
                                             from (select new_type.name,
                                                          old_type.parameters as old_params,
                                                          new_type.parameters as new_params
                                                   from merlin.activity_type new_type
                                                          left join (select name, parameters
                                                                     from merlin.activity_type
                                                                     where model_id = _old_model_id) old_type
                                                                    using (name)
                                                   where new_type.model_id = _new_model_id
                                                     and old_type.parameters <> new_type.parameters) type) types),
                                      '{}'::json);

  return row (json_build_object(
      'removed_activity_types', _removed_activity_types,
      'altered_activity_types', _altered_activity_types
              ))::hasura.check_model_compatability_return_value;
end
$$;


call migrations.mark_migration_applied('18');
