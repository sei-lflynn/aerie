import { jsExecute } from "../utils/codeRunner";
import { Pool } from "pg";
import { ActionResponse, ActionTask } from "../type/types";
import { ActionsDbManager } from "../db";

//export async function add({n} : { n : number}) :Promise<number> { debugger;return n; }

export async function runAction(task: ActionTask ): Promise<ActionResponse> {
  console.log("runAction", task);
  ActionsDbManager.init();
  const pool = ActionsDbManager.getDb();

  let jsRun: ActionResponse;
  try {
    jsRun = await jsExecute(task.actionJS, task.parameters, task.settings, task.auth, pool, task.workspaceId);
    // release DB connection
    await pool.end();
    return jsRun;
  } catch (e) {
    await pool.end();
    throw e;
  }
}
