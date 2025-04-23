alter table sequencing.command_dictionary
  add column command_dictionary_file_path text default null;

comment on column sequencing.command_dictionary.command_dictionary_file_path is e''
  'The location of the command dictionary file on the filesystem.';

call migrations.mark_migration_applied('17');
