package gov.nasa.jpl.aerie.merlin.worker.postgres;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PostgresQueryQueue {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public void addToQueue(Runnable task) {
    executor.submit(task);
  }

  /** Gracefully shut down the ExecutorService */
  public void shutdown() {
    executor.shutdown();
  }
}
