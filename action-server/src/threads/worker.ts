import { threadId } from "worker_threads";
import { jsExecute } from "../utils/codeRunner";
import { ActionResponse, ActionTask } from "../type/types";
import logger from "../utils/logger";
import {configuration} from "../config";
import pg from "pg";

const { AERIE_DB, AERIE_DB_HOST, AERIE_DB_PORT, ACTION_DB_USER, ACTION_DB_PASSWORD } = configuration();

function getWorkerDbPool() {
  // we currently have no way to pass DB connections from a parent-managed db pool to the worker process (preferred).
  // (Pools/clients contain data that cannot be serialized, causing a DataCloneError if passed to the worker)
  // instead, when an action is run, a worker creates a new Pool with one client, to get its own connection,
  // and closes it again when the action run is complete.
  return new pg.Pool({
    host: AERIE_DB_HOST,
    port: parseInt(AERIE_DB_PORT, 5432),
    database: AERIE_DB,
    user: ACTION_DB_USER,
    password: ACTION_DB_PASSWORD,
    // should have exactly one client/connection
    min: 1,
    max: 1
  });
}

export async function runAction(task: ActionTask): Promise<ActionResponse> {
  logger.info(`Worker [${threadId}] running task`);
  logger.info(`Parameters: ${JSON.stringify(task.parameters, null, 2)}`);
  logger.info(`Settings: ${JSON.stringify(task.settings, null, 2)}`);

  const pool = getWorkerDbPool();
  pool.on("error", (err) => {
    logger.error(`[${threadId}] pool error:`, err);
  });

  const client = await pool.connect();
  logger.info(`[${threadId}] Connected to DB`);

  let jsRun: ActionResponse;
  try {
    jsRun = await jsExecute(task.actionJS, task.parameters, task.settings, task.auth, client, task.workspaceId);
    logger.info(`[${threadId}] done executing`);
    if (client) await client.release();
    await pool.end();
    logger.info(`[${threadId}] released DB connection`);
    return jsRun;
  } catch (e) {
    logger.info(`[${threadId}] error while executing`);
    if (client) await client.release();
    await pool.end();
    logger.info(`[${threadId}] released DB connection`);
    throw e;
  }
}
