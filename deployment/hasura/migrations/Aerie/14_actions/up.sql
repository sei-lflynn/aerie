create role action_server;

create schema actions;

grant create, usage on schema actions to action_server;
grant select, insert, update, delete on all tables in schema actions to action_server;
grant execute on all routines in schema actions to action_server;

alter default privileges in schema actions grant select, insert, update, delete on tables to action_server;
alter default privileges in schema actions grant execute on routines to action_server;

grant create, usage on schema sequencing to action_server;
grant select, insert, update, delete on table sequencing.user_sequence to action_server;

create type actions.action_run_status as enum ('pending', 'in-progress', 'failed', 'complete');

create table actions.action_definition (
  id integer generated always as identity,

  name text not null,
  description text null,
  parameter_schema jsonb not null default '{}'::jsonb,
  settings_schema jsonb not null default '{}'::jsonb,
  settings jsonb not null,

  action_file_id integer not null,
  workspace_id integer not null,

  created_at timestamptz not null default now(),
  owner text,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint action_definition_synthetic_key
    primary key (id),

  foreign key (workspace_id)
    references sequencing.workspace (id)
    on delete set null,
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  constraint action_definition_references_action_file
    foreign key (action_file_id)
      references merlin.uploaded_file
      on update cascade
      on delete restrict,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table actions.action_definition is e''
  'User provided Javascript code that will be invoked by Aerie actions and ran on an Aerie server.';
comment on column actions.action_definition.name is e''
  'The name of the action.';
comment on column actions.action_definition.description is e''
  'The description of the action.';
comment on column actions.action_definition.parameter_schema is e''
  'The JSON schema representing the action''s parameters.';
comment on column actions.action_definition.settings_schema is e''
  'The JSON schema representing the action''s settings.';
comment on column actions.action_definition.settings is e''
  'The values provided for the action''s settings.';
comment on column actions.action_definition.action_file_id is e''
  'The ID of the uploaded action file.';
comment on column actions.action_definition.workspace_id is e''
  'The ID of the workspace the action is part of.';

create trigger set_timestamp
  before update on actions.action_definition
  for each row
  execute function util_functions.set_updated_at();

create function actions.notify_action_definition_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_definition_id,
                 action_file_path) as
           (
             select NEW.id,
                    encode(uf.path, 'escape') as path
             from merlin.uploaded_file uf
             where uf.id = NEW.action_file_id
           )
    select pg_notify('action_definition_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_definition_inserted
  after insert on actions.action_definition
  for each row
execute function actions.notify_action_definition_inserted();

create table actions.action_run (
  id integer generated always as identity,

  settings jsonb not null,
  parameters jsonb not null,
  logs text,
  error jsonb,
  results jsonb,
  status actions.action_run_status not null default 'pending',

  action_definition_id integer not null,

  created_by text,
  created_at timestamptz not null default now(),
  duration integer,

  constraint action_run_synthetic_key
    primary key (id),
  foreign key (action_definition_id)
    references actions.action_definition (id)
    on delete set null,
  foreign key (created_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table actions.action_run is e''
  'The record of a single run of an action.';
comment on column actions.action_run.settings is e''
  'The supplied settings for the run of the action.';
comment on column actions.action_run.parameters is e''
  'The supplied parameters for the run of the action.';
comment on column actions.action_run.logs is e''
  'The logs produced by the action run.';
comment on column actions.action_run.error is e''
  'The error produced by the action run.';
comment on column actions.action_run.results is e''
  'The results produced by the action run.';
comment on column actions.action_run.status is e''
  'The status of the action run.';
comment on column actions.action_run.duration is e''
  'The duration of the action run, if it has completed; null otherwise';
comment on column actions.action_run.action_definition_id is e''
  'The ID of the definition of the action.';

create function actions.notify_action_run_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_run_id,
                 settings,
                 parameters,
                 action_definition_id,
                 workspace_id,
                 action_file_path) as
           (
             select NEW.id,
                    NEW.settings,
                    NEW.parameters,
                    NEW.action_definition_id,
                    ad.workspace_id,
                    encode(uf.path, 'escape') as path
             from actions.action_definition ad
                    left join merlin.uploaded_file uf on uf.id = ad.action_file_id
                    where ad.id = NEW.action_definition_id
           )
    select pg_notify('action_run_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_run_inserted
  after insert on actions.action_run
  for each row
execute function actions.notify_action_run_inserted();

call migrations.mark_migration_applied('14');
