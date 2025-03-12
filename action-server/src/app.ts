import express from "express";
import { configuration } from "./config";
import { corsMiddleware, jsonErrorMiddleware } from "./middleware";
import { ActionWorkerPool } from "./threads/workerPool";
import { cleanup, setupListeners } from "./listeners/dbListeners";

const port = configuration().PORT;

// init express app and middleware
const app = express();
app.use(express.json()); // Middleware for parsing JSON bodies
app.use(corsMiddleware); // TODO: set more strict CORS rules
app.use(jsonErrorMiddleware);

const server = app.listen(port, async () => {
  console.debug(`Server running on port ${port}`);

  try {
    // init the pool of workers that will execute actions
    ActionWorkerPool.setup();
    // init the pg database listners
    await setupListeners();
  } catch (error) {
    console.error("Failed to initialize application:", error);
    process.exit(1);
  }
});

// handle termination signals
process.on("SIGINT", () => cleanup(server));
process.on("SIGTERM", () => cleanup(server));
