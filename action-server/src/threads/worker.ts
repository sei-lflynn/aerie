import { threadId } from "worker_threads";
import { jsExecute } from "../utils/codeRunner";
import { ActionResponse, ActionTask } from "../type/types";
import { ActionsDbManager } from "../db";
import logger from "../utils/logger";

export async function runAction(task: ActionTask): Promise<ActionResponse> {
  logger.info(`Worker [${threadId}] running task`);
  logger.info(`Parameters: ${JSON.stringify(task.parameters, null, 2)}`);
  logger.info(`Settings: ${JSON.stringify(task.parameters, null, 2)}`);

  ActionsDbManager.init();
  const pool = ActionsDbManager.getDb();
  pool.on("error", (err) => {
    logger.error(`[${threadId}] pool error:`, err);
  });
  // create a client so we can reuse the DB connection
  // NOTE: Attempting to pass a pool object from the main thread to a worker
  // fails because the pool contains data that cannot be serialized.
  // This leads to a DOMException: DataCloneError during the cloning process.
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
