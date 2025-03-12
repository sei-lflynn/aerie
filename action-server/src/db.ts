import type { Pool, PoolConfig } from "pg";
import pg from "pg";
import { configuration } from "./config";
// import getLogger from './utils/logger.js';

const { Pool: DbPool } = pg;

const { AERIE_DB, AERIE_DB_HOST, AERIE_DB_PORT, ACTION_DB_USER, ACTION_DB_PASSWORD } = configuration();
// const logger = getLogger('packages/db/db');
const logger = console;

export class ActionsDbManager {
  private static pool: Pool;

  static getDb(): Pool {
    return ActionsDbManager.pool;
  }

  static init() {
    try {
      const config: PoolConfig = {
        host: AERIE_DB_HOST,
        port: parseInt(AERIE_DB_PORT, 10),
        database: AERIE_DB,
        user: ACTION_DB_USER,
        password: ACTION_DB_PASSWORD,
      };

      logger.info(`Creating PG pool`);
      logger.debug(`
      {
         database: ${config.database},
         host: ${config.host},
         port: ${config.port},
         user: ${config.user}
      }`);

      ActionsDbManager.pool = new DbPool(config);
    } catch (error) {
      logger.error(error);
    }
  }
}
