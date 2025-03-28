import * as vm from "node:vm";
import type { ActionResponse } from "../type/types";
import { ActionsAPI } from "aerie-actions/dist/index";
import { PoolClient } from "pg";
import { createLogger, format, transports } from "winston";

// todo put this inside a more limited closure scope or it will get reused...
// const logBuffer: string[] = [];

function injectLogger(oldConsole: any, logBuffer: string[]) {
  // inject a winston logger to be passed to the action VM, replacing its normal `console`,
  // so we can capture the console outputs and return them with the action results
  const logger = createLogger({
    level: "debug", // todo allow user to set log level
    format: format.combine(
        format.timestamp(),
        format.printf(({ level, message, timestamp }) => {
          const logLine = `${timestamp} [${level.toUpperCase()}] ${message}`;
          logBuffer.push(logLine);
          return logLine;
        })
    ),
    // todo log to console if log level is debug
    transports: [new transports.Console()], // optional, for debugging
  });

  return {
    ...oldConsole,
    log: (...args: any[]) => logger.info(args.join(" ")),
    debug: (...args: any[]) => logger.debug(args.join(" ")),
    info: (...args: any[]) => logger.info(args.join(" ")),
    warn: (...args: any[]) => logger.warn(args.join(" ")),
    error: (...args: any[]) => logger.error(args.join(" "))
  }
}

function getGlobals() {
  let aerieGlobal = Object.defineProperties({ ...global }, Object.getOwnPropertyDescriptors(global));
  aerieGlobal.exports = {};
  aerieGlobal.require = require;
  aerieGlobal.__dirname = __dirname;
  // todo: pass env variables from the parent process?
  return aerieGlobal;
}

export const jsExecute = async (
  code: string,
  parameters: Record<string, any>,
  settings: Record<string, any>,
  authToken: string | undefined,
  client: PoolClient,
  workspaceId: number,
): Promise<ActionResponse> => {
  // create a clone of the global object (including getters/setters/non-enumerable properties)
  // to be passed to the context so it has access to eg. node built-ins
  const aerieGlobal = getGlobals();
  // inject custom logger to capture logs from action run
  let logBuffer: string[] = [];
  aerieGlobal.console = injectLogger(aerieGlobal.console, logBuffer);

  const context = vm.createContext(aerieGlobal);

  try {
    vm.runInContext(code, context);
    // todo: main runs outside of VM - is that OK?
    const actionsAPI = new ActionsAPI(client, workspaceId);
    const results = await context.main(parameters, settings, actionsAPI);
    return { results, console: logBuffer, errors: null };
  } catch (err: any) {
    // wrap `throw 10` into a `new throw(10)`
    let errorResponse: Error;
    if ((err !== null && typeof err !== "object") || !("message" in err && "stack" in err)) {
      errorResponse = new Error(String(err));
    } else {
      errorResponse = err;
    }
    // also push errors into run logs - useful to have them there
    if(errorResponse.message) aerieGlobal.console.error(errorResponse.message);
    if(errorResponse.stack) aerieGlobal.console.error(errorResponse.stack);
    if(errorResponse.cause) aerieGlobal.console.error(errorResponse.cause);

    return Promise.resolve({
      results: null,
      console: logBuffer,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    });
  }
};

// todo correct return type for schemas?
export const extractSchemas = async (code: string): Promise<any> => {
  // todo: do we need to pass globals/console for this part?

  // need to initialize exports for the cjs module to work correctly
  const aerieGlobal = getGlobals();
  const context = vm.createContext(aerieGlobal);

  try {
    vm.runInContext(code, context);
    const { paramDefs, settingDefs } = context.exports;
    return { paramDefs, settingDefs };
  } catch (error: any) {
    // wrap `throw 10` into a `new throw(10)`
    let errorResponse: Error;
    if ((error !== null && typeof error !== "object") || !("message" in error && "stack" in error)) {
      errorResponse = new Error(String(error));
    } else {
      errorResponse = error;
    }
    return Promise.resolve({
      results: null,
      errors: {
        stack: errorResponse.stack,
        message: errorResponse.message,
        cause: errorResponse.cause,
      },
    });
  }
};
