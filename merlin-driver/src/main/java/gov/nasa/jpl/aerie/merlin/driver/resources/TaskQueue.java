package gov.nasa.jpl.aerie.merlin.driver.resources;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TaskQueue {
  private static final ExecutorService executor = Executors.newSingleThreadExecutor();

  public static void addToQueue(Runnable task) {
    executor.submit(task);
  }

  /** Gracefully shut down the ExecutorService */
  public static void shutdown() {
    executor.shutdown();
  }
}
