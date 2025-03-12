import { Piscina } from "piscina";
import * as path from "node:path";
import { ActionResponse, ActionTask } from "../type/types";
import { configuration } from "../config";

export class ActionWorkerPool {
  private static piscina: Piscina<any, any>;

  static setup() {
    ActionWorkerPool.piscina = new Piscina({
      filename: path.resolve(__dirname, "worker.js"),
      maxThreads: parseInt(configuration().ACTION_MAX_WORKER_NUM),
      minThreads: parseInt(configuration().ACTION_WORKER_NUM),
    });
  }

  static async submitTask(task: ActionTask): Promise<ActionResponse> {
    // todo: maintain a custom queue for enqueueing run requests *by workspace*
    if (!ActionWorkerPool.piscina) {
      throw new Error("Worker pool not initialized");
    }

    try {
      return await ActionWorkerPool.piscina.run(task, { name: "runAction" });
    } catch (error) {
      console.error("Task submission failed:", error);
      throw error;
    }
  }
}
