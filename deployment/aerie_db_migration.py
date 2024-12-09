#!/usr/bin/env python3
"""Migrate the Aerie Database"""

import os
import argparse
import sys
import shutil
import subprocess
from dotenv import load_dotenv
import requests

def clear_screen():
  os.system('cls' if os.name == 'nt' else 'clear')


def exit_with_error(message: str, exit_code=1):
  """
  Exit the program with the specified error message and exit code.

  :param message: Error message to display before exiting.
  :param exit_code: Error code to exit with. Defaults to 1.
  """
  print("\033[91mError\033[0m: "+message)
  sys.exit(exit_code)


class Hasura:
  """
  Class for communicating with Hasura via the CLI and API.
  """
  command_suffix = ''
  migrate_suffix = ''
  endpoint = ''
  admin_secret = ''
  db_name = 'Aerie'
  current_version = 0

  def __init__(self, endpoint: str, admin_secret: str, hasura_path: str,  env_path: str, db_name='Aerie'):
    """
    Initialize a Hasura object.

    :param endpoint: The http(s) endpoint for the Hasura instance.
    :param admin_secret: The admin secret for the Hasura instance.
    :param hasura_path: The directory containing the config.yaml and migrations folder for the Hasura instance.
    :param env_path: The path to the envfile, if provided.
    :param db_name: The name that the Hasura instance calls the database. Defaults to 'Aerie'.
    """
    self.admin_secret = admin_secret
    self.db_name = db_name

    # Sanitize endpoint
    self.endpoint = endpoint
    self.endpoint = self.endpoint.strip()
    self.endpoint = self.endpoint.rstrip('/')

    # Set up the suffix flags to use when calling the Hasura CLI
    self.command_suffix = f'--skip-update-check --project {hasura_path}'
    if env_path:
      self.command_suffix += f' --envfile {env_path}'

    # Set up the suffix flags to use when calling the 'migrate' subcommand on the CLI
    self.migrate_suffix = f"--database-name {self.db_name} --endpoint {self.endpoint} --admin-secret '{self.admin_secret}'"

    # Check that Hasura CLI is installed
    if not shutil.which('hasura'):
      sys.exit(f'Hasura CLI is not installed. Exiting...')
    else:
      self.execute('version')

    # Mark the current schema version in Hasura
    self.current_version = self.mark_current_version()

  def execute(self, subcommand: str, flags='', no_output=False) -> int:
    """
    Execute an arbitrary "hasura" command.

    :param subcommand: The subcommand to execute.
    :param flags: The flags to be passed to the subcommand.
    :param no_output: If true, swallows both STDERR and STDOUT output from the command.
    :return: The exit code of the command.
    """
    command = f'hasura {subcommand} {flags} {self.command_suffix}'
    if no_output:
      command += ' > /dev/null 2>&1'
    return os.system(command)

  def migrate(self, subcommand: str, flags='', no_output=False) -> int:
    """
    Execute a "hasura migrate" subcommand.

    :param subcommand: A subcommand of "hasura migrate"
    :param flags: Flags specific to the subcommand call to be passed.
    :param no_output: If true, swallows both STDERR and STDOUT output from the command.
    :return: The exit code of the command.
    """
    command = f'hasura migrate {subcommand} {flags} {self.migrate_suffix} {self.command_suffix}'
    if no_output:
      command += ' > /dev/null 2>&1'
    return os.system(command)

  def get_migrate_output(self, subcommand: str, flags='') -> [str]:
    """
    Get the output of a "hasura migrate" subcommand.

    :param subcommand: A subcommand of "hasura migrate"
    :param flags: Flags specific to the subcommand call to be passed.
    :return: The STDOUT response of the subcommand, split on newlines.
    """
    command = f'hasura migrate {subcommand} {flags} {self.migrate_suffix} {self.command_suffix}'
    return subprocess.getoutput(command).split("\n")

  def mark_current_version(self) -> int:
    """
    Queries the database behind the Hasura instance for its current schema information.
    Ensures that all applied migrations are marked as "applied" in Hasura's internal migration tracker.

    :return: The migration the underlying database is currently on
    """
    # Query the database
    run_sql_url = f'{self.endpoint}/v2/query'
    headers = {
      "content-type": "application/json",
      "x-hasura-admin-secret": self.admin_secret,
      "x-hasura-role": "admin"
    }
    body = {
      "type": "run_sql",
      "args": {
        "source": self.db_name,
        "sql": "SELECT migration_id FROM migrations.schema_migrations;",
        "read_only": True
      }
    }
    session = requests.Session()
    resp = session.post(url=run_sql_url, headers=headers, json=body)
    if not resp.ok:
      exit_with_error("Error while fetching current schema information.")

    migration_ids = resp.json()['result']
    if migration_ids.pop(0)[0] != 'migration_id':
      exit_with_error("Error while fetching current schema information.")

    # migration_ids now looks like [['0'], ['1'], ... ['n']]
    prev_id = -1
    cur_id = 0
    for i in migration_ids:
      cur_id = int(i[0])
      if cur_id != prev_id + 1:
        exit_with_error(f'Gap detected in applied migrations. \n\tLast migration: {prev_id} \tNext migration: {cur_id}'
                        f'\n\tTo resolve, manually revert all migrations following {prev_id}, then run this script again.')
      # Ensure migration is marked as applied
      self.migrate('apply', f'--skip-execution --version {cur_id}', no_output=True)
      prev_id = cur_id

    return cur_id


class DB_Migration:
  """
  Container class for Migration steps to be applied/reverted.
  """
  steps = []
  migrations_folder = ''
  def __init__(self, migrations_folder: str, reverse: bool):
    """
    :param migrations_folder: Folder where the migrations are stored.
    :param reverse: If true, reverses the list of migration steps.
    """
    self.migrations_folder = migrations_folder
    try:
      for root, dirs, files in os.walk(migrations_folder):
        if dirs:
          self.add_migration_step(dirs)
    except FileNotFoundError as fne:
      exit_with_error(str(fne).split("]")[1])
    if len(self.steps) <= 0:
      exit_with_error("No database migrations found.")
    if reverse:
      self.steps.reverse()

  def add_migration_step(self, _migration_step):
    self.steps = sorted(_migration_step, key=lambda x: int(x.split('_')[0]))


def step_by_step_migration(hasura: Hasura, db_migration: DB_Migration, apply: bool):
  display_string = "\n\033[4mMIGRATION STEPS AVAILABLE:\033[0m\n"
  _output = hasura.get_migrate_output('status')
  del _output[0:3]
  display_string += _output[0] + "\n"

  # Filter out the steps that can't be applied given the current mode and currently applied steps
  available_steps = db_migration.steps.copy()
  for i in range(1, len(_output)):
    split = list(filter(None, _output[i].split(" ")))

    if len(split) >= 5 and "Not Present" == (split[2]+" "+split[3]):
      print("\n\033[91mError\033[0m: Migration files exist on server that do not exist on this machine. "
            "Synchronize files and try again.\n")
      input("Press Enter to continue...")
      return

    folder = os.path.join(db_migration.migrations_folder, f'{split[0]}_{split[1]}')
    if apply:
      # If there are four words, they must be "<NUMBER> <MIGRATION NAME> Present Present"
      if (len(split) == 4 and "Present" == split[-1]) or (not os.path.isfile(os.path.join(folder, 'up.sql'))):
        available_steps.remove(f'{split[0]}_{split[1]}')
      else:
        display_string += _output[i] + "\n"
    else:
      # If there are only five words, they must be "<NUMBER> <MIGRATION NAME> Present Not Present"
      if (len(split) == 5 and "Not Present" == (split[-2] + " " + split[-1])) or (not os.path.isfile(os.path.join(folder, 'down.sql'))):
        available_steps.remove(f'{split[0]}_{split[1]}')
      else:
        display_string += _output[i] + "\n"

  if available_steps:
    print(display_string)
  else:
    print("\nNO MIGRATION STEPS AVAILABLE\n")

  for step in available_steps:
    print("\033[4mCURRENT STEP:\033[0m\n")
    timestamp = step.split("_")[0]

    if apply:
      hasura.migrate('apply', f'--version {timestamp} --dry-run --log-level WARN')
    else:
      hasura.migrate('apply', f'--version {timestamp} --type down --dry-run --log-level WARN')

    print()
    _value = ''
    while _value != "y" and _value != "n" and _value != "q" and _value != "quit":
      if apply:
        _value = input(f'Apply {step}? (y/n/\033[4mq\033[0muit): ').lower()
      else:
        _value = input(f'Revert {step}? (y/n/\033[4mq\033[0muit): ').lower()

    if _value == "q" or _value == "quit":
      sys.exit()
    if _value == "y":
      if apply:
        print('Applying...')
        exit_code = hasura.migrate('apply', f'--version {timestamp} --type up')
      else:
        print('Reverting...')
        exit_code = hasura.migrate('apply', f'--version {timestamp} --type down')
      hasura.execute('metadata reload')
      print()
      if exit_code != 0:
        return
    elif _value == "n":
      return
  input("Press Enter to continue...")


def bulk_migration(hasura: Hasura, apply: bool):
  # Migrate the database
  exit_with = 0
  if apply:
    hasura.migrate('apply', f'--dry-run --log-level WARN')
    exit_code = hasura.migrate('apply')
    if exit_code != 0:
      exit_with = 1
  else:
    hasura.migrate('apply', f'--down {hasura.current_version} --dry-run --log-level WARN')
    exit_code = hasura.migrate('apply', f'--down {hasura.current_version}')
    if exit_code != 0:
      exit_with = 1

  hasura.execute('metadata reload')

  # Show the result after the migration
  print(f'\n###############'
        f'\nDatabase Status'
        f'\n###############')
  _output = hasura.get_migrate_output('status')
  del _output[0:3]
  print("\n".join(_output))
  exit(exit_with)


def loadConfigFile(endpoint: str, secret: str, config_folder: str) -> (str, str):
  """
  Extract the endpoint and admin secret from a Hasura config file.
  Values passed as arguments take priority over the contents of the config file.

  :param endpoint: Initial value of the endpoint for Hasura. Will be extracted if empty.
  :param secret: Initial value of the admin secret for Hasura. Will be extracted if empty.
  :param config_folder: Folder to look for the config file in.
  :return: A tuple containing the Hasura endpoint and the Hasura admin secret.
  """
  hasura_endpoint = endpoint
  hasura_admin_secret = secret

  # Check if config.YAML exists
  configPath = os.path.join(config_folder, 'config.yaml')
  if not os.path.isfile(configPath):
    # Check for .YML
    configPath = os.path.join(config_folder, 'config.yml')
    if not os.path.isfile(configPath):
      errorMsg = "HASURA_GRAPHQL_ENDPOINT and HASURA_GRAPHQL_ADMIN_SECRET" if not endpoint and not secret \
        else "HASURA_GRAPHQL_ENDPOINT" if not endpoint \
        else "HASURA_GRAPHQL_ADMIN_SECRET"
      errorMsg += " must be defined by either environment variables or in a config.yaml located in " + config_folder + "."
      exit_with_error(errorMsg)

  # Extract admin secret and/or endpoint from the config.yaml, if they were not already set
  with open(configPath) as configFile:
    for line in configFile:
      if hasura_endpoint and hasura_admin_secret:
        break
      line = line.strip()
      if line.startswith("endpoint") and not hasura_endpoint:
        hasura_endpoint = line.removeprefix("endpoint:").strip()
        continue
      if line.startswith("admin_secret") and not hasura_admin_secret:
        hasura_admin_secret = line.removeprefix("admin_secret:").strip()
        continue

  if not hasura_endpoint or not hasura_admin_secret:
    errorMsg = "HASURA_GRAPHQL_ENDPOINT and HASURA_GRAPHQL_ADMIN_SECRET" if not hasura_endpoint and not hasura_admin_secret \
      else "HASURA_GRAPHQL_ENDPOINT" if not hasura_endpoint \
      else "HASURA_GRAPHQL_ADMIN_SECRET"
    errorMsg += " must be defined by either environment variables or in a config.yaml located in " + config_folder + "."
    exit_with_error(errorMsg)

  return hasura_endpoint, hasura_admin_secret


def createArgsParser() -> argparse.ArgumentParser:
  """
  Create an ArgumentParser for this script.
  """
  # Create a cli parser
  parser = argparse.ArgumentParser(description=__doc__)

  # Applying and Reverting are exclusive arguments
  exclusive_args = parser.add_mutually_exclusive_group(required=True)

  # Add arguments
  exclusive_args.add_argument(
    '-a', '--apply',
    help='apply migration steps to the database',
    action='store_true')

  exclusive_args.add_argument(
    '-r', '--revert',
    help='revert migration steps to the databases',
    action='store_true')

  parser.add_argument(
    '--all',
    help='apply[revert] ALL unapplied[applied] migration steps to the database',
    action='store_true')

  parser.add_argument(
    '-p', '--hasura-path',
    help='directory containing the config.yaml and migrations folder for the venue. defaults to ./hasura',
    default='./hasura')

  parser.add_argument(
    '-e', '--env-path',
    help='envfile to load envvars from.')

  parser.add_argument(
    '--endpoint',
    help="http(s) endpoint for the venue's Hasura instance",
    required=False)

  parser.add_argument(
    '--admin_secret',
    help="admin secret for the venue's Hasura instance",
    required=False)

  return parser


def main():
  # Generate arguments
  args = migrateArgsParser().parse_args()

  HASURA_PATH = args.hasura_path
  MIGRATION_PATH = os.path.abspath(HASURA_PATH+"/migrations/Aerie")

  if args.env_path:
    if not os.path.isfile(args.env_path):
      exit_with_error(f'Specified envfile does not exist: {args.env_path}')
    load_dotenv(args.env_path)

  # Grab the credentials from the environment if needed
  hasura_endpoint = args.endpoint if args.endpoint else os.environ.get('HASURA_GRAPHQL_ENDPOINT', "")
  hasura_admin_secret = args.admin_secret if args.admin_secret else os.environ.get('HASURA_GRAPHQL_ADMIN_SECRET', "")

  if not (hasura_endpoint and hasura_admin_secret):
    (e, s) = loadConfigFile(hasura_endpoint, hasura_admin_secret, HASURA_PATH)
    hasura_endpoint = e
    hasura_admin_secret = s

  hasura = Hasura(endpoint=hasura_endpoint,
                  admin_secret=hasura_admin_secret,
                  db_name="Aerie",
                  hasura_path=os.path.abspath(HASURA_PATH),
                  env_path=os.path.abspath(args.env_path) if args.env_path else None)

  # Navigate to the hasura directory
  os.chdir(HASURA_PATH)

  clear_screen()
  print(f'\n###############################'
        f'\nAERIE DATABASE MIGRATION HELPER'
        f'\n###############################')
  # Enter step-by-step mode if not otherwise specified
  if not args.all:
    # Find all migration folders for the database
    migration = DB_Migration(MIGRATION_PATH, args.revert)

    # Go step-by-step through the migrations available for the selected database
    step_by_step_migration(hasura, migration, args.apply)
  else:
    bulk_migration(hasura, args.apply)


if __name__ == "__main__":
  main()
