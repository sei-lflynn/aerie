
import { Piscina } from "piscina";
import * as path from "node:path";
import { ActionTask } from "../type/types";

export class ActionWorkerPool {
  private static piscina : Piscina<any, any>;

  static setup(){
    ActionWorkerPool.piscina = new Piscina({
      filename: path.resolve(__dirname, "worker.js"),
      maxThreads: 1,
      minThreads: 1
    });
  }

  static async submitTask(task:ActionTask){
    try {
      const result = await ActionWorkerPool.piscina.run(task,
          { name: 'runAction' }
      );
      return result;
    } catch (error) {
      console.error('Task submission failed:', error);
      throw error;
    }
  }
}



