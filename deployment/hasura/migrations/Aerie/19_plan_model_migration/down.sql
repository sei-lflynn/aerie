-- Remove plan migration functions
drop function hasura.migrate_plan_to_model();
drop table hasura.migrate_plan_to_model_return_value;

drop function hasura.check_model_compatibility;
drop table hasura.check_model_compatibility_return_value;

-- Remove model_id from plan snapshot
alter table merlin.plan_snapshot
  drop column model_id;

-- Restore plan snapshot functions to state before this migration
drop function merlin.create_snapshot(_plan_id integer);
-- Captures the state of a plan and all of its activities
create function merlin.create_snapshot(_plan_id integer)
  returns integer
  language plpgsql as $$
begin
  return merlin.create_snapshot(_plan_id, null, null, null);
end
$$;

drop function merlin.create_snapshot(_plan_id integer, _snapshot_name text, _description text, _user text);
create function merlin.create_snapshot(_plan_id integer, _snapshot_name text, _description text, _user text)
  returns integer -- snapshot id inserted into the table
  language plpgsql as $$
declare
  validate_plan_id integer;
  inserted_snapshot_id integer;
begin
  select id from merlin.plan where plan.id = _plan_id into validate_plan_id;
  if validate_plan_id is null then
    raise exception 'Plan % does not exist.', _plan_id;
  end if;

  insert into merlin.plan_snapshot(plan_id, revision, snapshot_name, description, taken_by)
  select id, revision, _snapshot_name, _description, _user
  from merlin.plan where id = _plan_id
  returning snapshot_id into inserted_snapshot_id;
  insert into merlin.plan_snapshot_activities(
    snapshot_id, id, name, source_scheduling_goal_id, created_at, created_by,
    last_modified_at, last_modified_by, start_offset, type,
    arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start)
  select
    inserted_snapshot_id,                              -- this is the snapshot id
    id, name, source_scheduling_goal_id, created_at, created_by, -- these are the rest of the data for an activity row
    last_modified_at, last_modified_by, start_offset, type,
    arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start
  from merlin.activity_directive where activity_directive.plan_id = _plan_id;
  insert into merlin.preset_to_snapshot_directive(preset_id, activity_id, snapshot_id)
  select ptd.preset_id, ptd.activity_id, inserted_snapshot_id
  from merlin.preset_to_directive ptd
  where ptd.plan_id = _plan_id;
  insert into tags.snapshot_activity_tags(snapshot_id, directive_id, tag_id)
  select inserted_snapshot_id, directive_id, tag_id
  from tags.activity_directive_tags adt
  where adt.plan_id = _plan_id;

  --all snapshots in plan_latest_snapshot for plan plan_id become the parent of the current snapshot
  insert into merlin.plan_snapshot_parent(snapshot_id, parent_snapshot_id)
  select inserted_snapshot_id, snapshot_id
  from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;

  --remove all of those entries from plan_latest_snapshot and add this new snapshot.
  delete from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;
  insert into merlin.plan_latest_snapshot(plan_id, snapshot_id) values (_plan_id, inserted_snapshot_id);

  return inserted_snapshot_id;
end;
$$;

comment on function merlin.create_snapshot(integer) is e''
  'See comment on create_snapshot(integer, text, text, text)';

comment on function merlin.create_snapshot(integer, text, text, text) is e''
  'Create a snapshot of the specified plan. A snapshot consists of:'
  '  - The plan''s id and revision'
  '  - All the activities in the plan'
  '  - The preset status of those activities'
  '  - The tags on those activities'
  '  - When the snapshot was taken'
  '  - Optionally: who took the snapshot, a name for the snapshot, a description of the snapshot';

drop function merlin.create_merge_request();
create function merlin.create_merge_request(plan_id_supplying integer, plan_id_receiving integer, request_username text)
  returns integer
  language plpgsql as $$
declare
  merge_base_snapshot_id integer;
  validate_planIds integer;
  supplying_snapshot_id integer;
  merge_request_id integer;
begin
  if plan_id_receiving = plan_id_supplying then
    raise exception 'Cannot create a merge request between a plan and itself.';
  end if;
  select id from merlin.plan where plan.id = plan_id_receiving into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan receiving changes (Plan %) does not exist.', plan_id_receiving;
  end if;
  select id from merlin.plan where plan.id = plan_id_supplying into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan supplying changes (Plan %) does not exist.', plan_id_supplying;
  end if;

  select merlin.create_snapshot(plan_id_supplying) into supplying_snapshot_id;

  select merlin.get_merge_base(plan_id_receiving, supplying_snapshot_id) into merge_base_snapshot_id;
  if merge_base_snapshot_id is null then
    raise exception 'Cannot create merge request between unrelated plans.';
  end if;

  insert into merlin.merge_request(plan_id_receiving_changes, snapshot_id_supplying_changes, merge_base_snapshot_id, requester_username)
  values(plan_id_receiving, supplying_snapshot_id, merge_base_snapshot_id, request_username)
  returning id into merge_request_id;
  return merge_request_id;
end
$$;

drop procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer);
create procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer)
  language plpgsql as $$
declare
  _snapshot_name text;
  _plan_name text;
begin
  -- Input Validation
  select name from merlin.plan where id = _plan_id into _plan_name;
  if _plan_name is null then
    raise exception 'Cannot Restore: Plan with ID % does not exist.', _plan_id;
  end if;
  if not exists(select snapshot_id from merlin.plan_snapshot where snapshot_id = _snapshot_id) then
    raise exception 'Cannot Restore: Snapshot with ID % does not exist.', _snapshot_id;
  end if;
  if not exists(select snapshot_id from merlin.plan_snapshot where _snapshot_id = snapshot_id and _plan_id = plan_id ) then
    select snapshot_name from merlin.plan_snapshot where snapshot_id = _snapshot_id into _snapshot_name;
    if _snapshot_name is not null then
      raise exception 'Cannot Restore: Snapshot ''%'' (ID %) is not a snapshot of Plan ''%'' (ID %)',
        _snapshot_name, _snapshot_id, _plan_name, _plan_id;
    else
      raise exception 'Cannot Restore: Snapshot % is not a snapshot of Plan ''%'' (ID %)',
        _snapshot_id, _plan_name, _plan_id;
    end if;
  end if;

  -- Catch Plan_Locked
  call merlin.plan_locked_exception(_plan_id);

  -- Record the Union of Activities in Plan and Snapshot
  -- and note which ones have been added since the Snapshot was taken (in_snapshot = false)
  create temp table diff(
                          activity_id integer,
                          in_snapshot boolean not null
  );
  insert into diff(activity_id, in_snapshot)
  select id as activity_id, true
  from merlin.plan_snapshot_activities where snapshot_id = _snapshot_id;

  insert into diff (activity_id, in_snapshot)
  select activity_id, false
  from(
        select id as activity_id
        from merlin.activity_directive
        where plan_id = _plan_id
        except
        select activity_id
        from diff) a;

  -- Remove any added activities
  delete from merlin.activity_directive ad
    using diff d
  where (ad.id, ad.plan_id) = (d.activity_id, _plan_id)
    and d.in_snapshot is false;

  -- Upsert the rest
  insert into merlin.activity_directive (
    id, plan_id, name, source_scheduling_goal_id, created_at, created_by, last_modified_at, last_modified_by,
    start_offset, type, arguments, last_modified_arguments_at, metadata,
    anchor_id, anchored_to_start)
  select psa.id, _plan_id, psa.name, psa.source_scheduling_goal_id, psa.created_at, psa.created_by, psa.last_modified_at, psa.last_modified_by,
         psa.start_offset, psa.type, psa.arguments, psa.last_modified_arguments_at, psa.metadata,
         psa.anchor_id, psa.anchored_to_start
  from merlin.plan_snapshot_activities psa
  where psa.snapshot_id = _snapshot_id
  on conflict (id, plan_id) do update
    -- 'last_modified_at' and 'last_modified_arguments_at' are skipped during update, as triggers will overwrite them to now()
    set name = excluded.name,
        source_scheduling_goal_id = excluded.source_scheduling_goal_id,
        created_at = excluded.created_at,
        created_by = excluded.created_by,
        last_modified_by = excluded.last_modified_by,
        start_offset = excluded.start_offset,
        type = excluded.type,
        arguments = excluded.arguments,
        metadata = excluded.metadata,
        anchor_id = excluded.anchor_id,
        anchored_to_start = excluded.anchored_to_start;

  -- Tags
  delete from tags.activity_directive_tags adt
    using diff d
  where (adt.directive_id, adt.plan_id) = (d.activity_id, _plan_id);

  insert into tags.activity_directive_tags(directive_id, plan_id, tag_id)
  select sat.directive_id, _plan_id, sat.tag_id
  from tags.snapshot_activity_tags sat
  where sat.snapshot_id = _snapshot_id
  on conflict (directive_id, plan_id, tag_id) do nothing;

  -- Presets
  delete from merlin.preset_to_directive
  where plan_id = _plan_id;
  insert into merlin.preset_to_directive(preset_id, activity_id, plan_id)
  select pts.preset_id, pts.activity_id, _plan_id
  from merlin.preset_to_snapshot_directive pts
  where pts.snapshot_id = _snapshot_id
  on conflict (activity_id, plan_id)
    do update	set preset_id = excluded.preset_id;

  -- Clean up
  drop table diff;
end
$$;

comment on procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer) is e''
  'Restore a plan to its state described in the given snapshot.';

-- Drop model_id column from simulation_dataset
alter table merlin.simulation_dataset
  drop column model_id;

call migrations.mark_migration_rolled_back('18');
