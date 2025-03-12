import { threadId } from 'worker_threads';
import { jsExecute } from "../utils/codeRunner";
import { ActionResponse, ActionTask } from "../type/types";
import { ActionsDbManager } from "../db";



//export async function add({n} : { n : number}) :Promise<number> { debugger;return n; }

export async function runAction(task: ActionTask ): Promise<ActionResponse> {
  console.log(`Worker [${threadId}] running task`);
  console.info(`Parameters: ${JSON.stringify(task.parameters, null, 2)}`);
  console.info(`Settings: ${JSON.stringify(task.parameters, null, 2)}`);

  // console.debug(task);
  ActionsDbManager.init();
  const pool = ActionsDbManager.getDb();
  pool.on('error', (err) => {
    console.error(`[${threadId}] pool error:`, err);
  });
  // create a client so we can reuse the DB connection
  const client = await pool.connect();
  console.log(`[${threadId}] Connected to DB`);

  let jsRun: ActionResponse;
  try {
    jsRun = await jsExecute(task.actionJS, task.parameters, task.settings, task.auth, client, task.workspaceId);
    console.log(`[${threadId}] done executing`);
    if(client) await client.release();
    await pool.end();
    console.log(`[${threadId}] released DB connection`);
    return jsRun;
  } catch (e) {
    console.log(`[${threadId}] error while executing`);
    if(client) await client.release();
    await pool.end();
    console.log(`[${threadId}] released DB connection`);
    throw e;
  }
}
