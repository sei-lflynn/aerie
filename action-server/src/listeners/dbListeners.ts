import { readFile } from "node:fs/promises";
import type http from "node:http";
import * as path from "node:path";
import type { Pool, PoolClient } from "pg";
import { configuration } from "../config";
import { ActionsDbManager } from "../db";
import { ActionWorkerPool } from "../threads/workerPool";
import type { ActionDefinitionInsertedPayload, ActionResponse, ActionRunInsertedPayload } from "../type/types";
import { extractSchemas } from "../utils/codeRunner";

let listenClient: PoolClient | undefined;

async function readFileFromStore(fileName: string): Promise<string> {
  // read file from aerie file store and return [resolve] it as a string
  const fileStoreBasePath = configuration().ACTION_LOCAL_STORE;
  const filePath = path.join(fileStoreBasePath, fileName);
  console.log(`path is ${filePath}`);
  return await readFile(filePath, "utf-8");
}

async function handleActionDefinition(payload: ActionDefinitionInsertedPayload) {
  console.log("action definition inserted");

  // read the action file and extract parameter/setting schemas
  const actionJS = await readFileFromStore(payload.action_file_path);
  // console.debug(actionJS);
  const schemas = await extractSchemas(actionJS);

  console.info(`schemas ${JSON.stringify(schemas, null, 2)}`);

  // todo: set schemas on the DB row?
  const pool = ActionsDbManager.getDb();
  const query = `
    UPDATE actions.action_definition
    SET
      parameter_schema = parameter_schema || $1::jsonb,
      settings_schema = settings_schema || $2::jsonb
    WHERE id = $3
      RETURNING *;
  `;

  try {
    const res = await pool.query(query, [
      JSON.stringify(schemas.paramDefs),
      JSON.stringify(schemas.settingDefs),
      payload.action_definition_id,
    ]);
    console.log("Updated action_definition:", res.rows[0]);
  } catch (error) {
    console.error("Error updating row:", error);
  }
}

async function handleActionRun(payload: ActionRunInsertedPayload) {
  const actionRunId = payload.action_run_id;
  const actionFilePath = payload.action_file_path;
  console.log(`action run ${actionRunId} inserted (${actionFilePath})`);
  console.info(payload);
  // event payload contains a file path for the action file which should be run
  const actionJS = await readFileFromStore(actionFilePath);

  // TODO: how to handle auth tokens??
  // const authToken = req.header("authorization");
  // if (!authToken) console.warn("No valid `authorization` header in action-run request");

  // todo: run the action file, put results in the same DB row and mark status as successful
  // todo: try/catch - need to handle errors manually since not in express handler?
  const { parameters, settings } = payload;
  const workspaceId = payload.workspace_id;
  const pool = ActionsDbManager.getDb(); // cant seralize pool as there is data that is unserializable DOMException: DataCloneError
  console.log(`Submitting task to worker pool for action run ${actionRunId}`);
  const start = performance.now();

  let run, taskError;
  try {
    run = (await ActionWorkerPool.submitTask({
      actionJS,
      parameters,
      settings,
      workspaceId,
    })) satisfies ActionResponse;
  } catch (error: any) {
    console.error("Error running task:", error);
    taskError = { message: error.message, stack: error.stack };
  }

  const duration = Math.round(performance.now() - start);
  const status = taskError || run?.errors ? "failed" : "complete";
  console.log(`Finished run ${actionRunId} in ${duration / 1000}s - ${status}`);
  console.info(run);

  const logStr = run ? run.console.join("\n") : "";

  // update action_run row in DB with status/results/errors/logs
  try {
    const res = await pool.query(
      `
      UPDATE actions.action_run
      SET
        status = $1,
        error = $2::jsonb,
        results = $3::jsonb,
        logs = $4,
        duration = $5
      WHERE id = $6
        RETURNING *;
    `,
      [
        status,
        JSON.stringify(taskError || run?.errors),
        run ? JSON.stringify(run.results) : undefined,
        logStr,
        duration,
        payload.action_run_id,
      ],
    );
    console.log("Updated action_run:", res.rows[0]);
  } catch (error) {
    console.error("Error updating row:", error);
  }
}

export async function setupListeners() {
  // initialize a database connection pool
  ActionsDbManager.init();
  const pool = ActionsDbManager.getDb();

  // todo: check for definitions/runs that may have been inserted while action-server was down (ie. missed notifs) & process them?

  const listenClient = await pool.connect();
  // these occur when user inserts row in `action_definition`, need to pre-process to extract the schemas
  listenClient.query("LISTEN action_definition_inserted");
  // these occur when a user inserts a row in the `action_run` table, signifying a run request
  listenClient.query("LISTEN action_run_inserted");

  listenClient.on("notification", async (msg) => {
    console.info(`PG notify event: ${JSON.stringify(msg, null, 2)}`);
    if (!msg.payload) {
      console.warn(`warning: PG event with no message or payload: ${JSON.stringify(msg, null, 2)}`);
      return;
    }
    const payload = JSON.parse(msg.payload);

    if (msg.channel === "action_definition_inserted") {
      // todo should these be awaited?
      await handleActionDefinition(payload);
    } else if (msg.channel === "action_run_inserted") {
      await handleActionRun(payload);
    }
  });
  console.log("Initialized PG event listeners");
}

export function cleanup(server: http.Server) {
  console.log("shutting down...");
  if (listenClient) {
    listenClient.release();
  }
  server.close(() => {
    console.log("server closed.");
    process.exit(0);
  });
}
