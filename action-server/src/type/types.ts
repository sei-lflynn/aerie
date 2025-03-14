import { Pool } from "pg";

export type ActionRunRequest = {
  actionJS: string;
  parameters: Record<string, any>;
  settings: Record<string, any>;
};

/* TODO: ActionResults should be defined by the actions API and imported */
export type ActionResults = {
  status: "FAILED" | "SUCCESS";
  data: any;
};

export type ConsoleOutput = {
  log: string[];
  debug: string[];
  info: string[];
  error: string[];
  warn: string[];
};

export type ActionTask = {
  actionJS: string;
  parameters: Record<string, any>;
  settings: Record<string, any>;
  auth?: string;
  workspaceId: number;
};

export type ActionDefinitionInsertedPayload = {
  action_definition_id: number;
  action_file_path: string;
};

export type ActionRunInsertedPayload = {
  action_run_id: string;
  settings: Record<string, any>;
  parameters: Record<string, any>;
  action_definition_id: number;
  workspace_id: number;
  action_file_path: string;
};

export type ActionResponse =
  | {
      results: ActionResults;
      console: string[];
      errors: null;
    }
  | {
      results: null;
      console: string[];
      errors: {
        stack: string | undefined;
        message: string;
        cause: unknown;
      }; // TODO: should this be an error array
    };
