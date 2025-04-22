import { Piscina } from "piscina";
import * as path from "node:path";
import { ActionResponse, ActionTask } from "../type/types";
import { configuration } from "../config";
import logger from "../utils/logger";
import { MessageChannel, MessagePort } from 'worker_threads';

export class ActionWorkerPool {
  private static piscina: Piscina<any, any>;
  public static abortControllerForActionRun: Map<string, AbortController> = new Map();
  public static messagePortsForActionRun: Map<string, MessagePort> = new Map();


  static setup() {
    this.piscina = new Piscina({
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

    // Create a new MessageChannel for this task
    const { port1, port2 } = new MessageChannel();
    task.message_port = port2;

    this.abortControllerForActionRun.set(task.action_run_id, task.abort_controller);
    this.messagePortsForActionRun.set(task.action_run_id, port1);

    console.log("Submitted new task with ID "+ task.action_run_id);


    try {
      return await ActionWorkerPool.piscina.run(task, { name: "runAction" , transferList: [port2]});
    } catch (error) {
      // todo: differentiate between task submission failure and cancellation
      console.log(error);
      logger.error("Task submission failed:", error);
      throw error;
    }
  }

  static cancelTask(action_run_id: string) {
    if (this.abortControllerForActionRun.has(action_run_id)) {
      // kill the task and delete from the abortControllers data structure
      // todo: graceful shutdown by passing signal to worker
      const port = this.messagePortsForActionRun.get(action_run_id);
      if (port) {
        port.postMessage({ type: 'abort' });
      } else {
        console.warn(`No port found for task ${action_run_id}`);
      }
      this.abortControllerForActionRun.get(action_run_id)?.abort();
      this.abortControllerForActionRun.delete(action_run_id);
      this.messagePortsForActionRun.delete(action_run_id);
    }
  }

  static removeFromMap(action_run_id: string) {
    if (this.abortControllerForActionRun.has(action_run_id)) {
      this.abortControllerForActionRun.delete(action_run_id);
    }
    if (this.messagePortsForActionRun.has(action_run_id)) {
      this.messagePortsForActionRun.delete(action_run_id);
    }
  }

}
