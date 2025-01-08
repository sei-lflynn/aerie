package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.DatabaseException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Consumer;

public class PostgresProfileStreamer implements Consumer<ResourceProfiles>, AutoCloseable {
  private final DataSource dataSource;
  private long datasetId;
  private PostgresQueryQueue queryQueue;

  public PostgresProfileStreamer(DataSource dataSource, long datasetId) throws SQLException {
    this.dataSource = dataSource;
    this.datasetId = datasetId;
    this.queryQueue = new PostgresQueryQueue();
  }

  @Override
  public void accept(final ResourceProfiles resourceProfiles) {
    queryQueue.addToQueue(() -> {
      try (var transaction = new PostgresProfileQueryHandler(dataSource, datasetId)) {
          transaction.uploadResourceProfiles(resourceProfiles);
      } catch (SQLException e) {
        throw new DatabaseException("Exception occurred while posting profiles.", e);
      }
    });
  }

  @Override
  public void close() {
    queryQueue.shutdown();
  }

}
