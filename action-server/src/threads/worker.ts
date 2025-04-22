import { threadId } from "worker_threads";
import { jsExecute } from "../utils/codeRunner";
import { ActionResponse, ActionTask } from "../type/types";
import logger from "../utils/logger";
import { configuration } from "../config";
import pg from "pg";
import { ActionWorkerPool } from "./workerPool";
import { MessageChannel, MessagePort } from "worker_threads";

const { AERIE_DB, AERIE_DB_HOST, AERIE_DB_PORT, ACTION_DB_USER, ACTION_DB_PASSWORD } = configuration();

let dbPool: pg.Pool | null = null;
let dbClient: pg.PoolClient | null = null;

function getDbPool() {
  // we currently have no way to pass DB connections from a parent-managed db pool to the worker process (preferred).
  // (Pools/clients contain data that cannot be serialized, causing a DataCloneError if passed to the worker)
  // instead, when an action is run, a worker creates a new Pool with one client, to get its own connection,
  // and closes it again when the action run is complete.

  if (dbPool) return dbPool;

  dbPool = new pg.Pool({
    host: AERIE_DB_HOST,
    port: parseInt(AERIE_DB_PORT, 5432),
    database: AERIE_DB,
    user: ACTION_DB_USER,
    password: ACTION_DB_PASSWORD,
    // should have exactly one client/connection
    min: 1,
    max: 1,
  });

  dbPool.on("error", (err) => {
    logger.error(`[${threadId}] pool error:`, err);
  });

  return dbPool;
}

async function getDbClient(): Promise<pg.PoolClient> {
  if (dbClient) return dbClient;

  const pool = getDbPool();
  dbClient = await pool.connect();
  return dbClient;
}

async function releaseDbPoolAndClient(): Promise<void> {
  if (dbClient) {
    dbClient.release();
    dbClient = null;
  }

  if (dbPool) {
    logger.info(`[${threadId}] Shutting down worker DB pool`);
    await dbPool.end();
    dbPool = null;
  }
}

export async function runAction(task: ActionTask): Promise<ActionResponse> {
  logger.info(`Worker [${threadId}] running task`);
  logger.info(`Parameters: ${JSON.stringify(task.parameters, null, 2)}`);
  logger.info(`Settings: ${JSON.stringify(task.settings, null, 2)}`);

  // Set up the message listener
  if (task.message_port) {
    task.message_port.on("message", async (msg) => {
      if (msg.type === "abort") {
        logger.info(`[${threadId}] Received abort message`);
        try {
          await releaseDbPoolAndClient();
          logger.info(`[${threadId}] Async cleanup complete`);
          task.message_port?.postMessage({ type: "cleanup_complete" });
        } catch (err) {
          logger.error(`[${threadId}] Error during async cleanup`, err);
        }
      }
    });
  }

  const client = await getDbClient();
  logger.info(`[${threadId}] Connected to DB`);

  // update this action run in the database to show "incomplete"
  try {
    logger.info(`[${threadId}] Attempting to mark action run ${task.action_run_id} as incomplete`);
    const res = await client.query(
      `
      UPDATE actions.action_run
      SET
        status = $1
      WHERE id = $2
        RETURNING *;
    `,
      ["incomplete", task.action_run_id],
    );
    logger.info("Updated action_run:", res.rows[0]);
  } catch (error) {
    logger.error("Error updating status of action_run:", error);
  }

  let jsRun: ActionResponse;
  try {
    jsRun = await jsExecute(task.actionJS, task.parameters, task.settings, task.auth, client, task.workspaceId);
    logger.info(`[${threadId}] done executing`);
    return jsRun;
  } catch (e) {
    logger.info(`[${threadId}] error while executing`);
    throw e;
  } finally {
    await releaseDbPoolAndClient();
    logger.info(`[${threadId}] released DB connection`);
    ActionWorkerPool.removeFromMap(task.action_run_id);
  }
}
