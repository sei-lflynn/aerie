alter table sequencing.command_dictionary
  drop column command_dictionary_file_path;

call migrations.mark_migration_rolled_back('18');
