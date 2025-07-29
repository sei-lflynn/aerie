package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.exceptions.JWTVerificationException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import io.javalin.Javalin;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.Plugin;
import io.javalin.validation.ValidationException;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import static io.javalin.apibuilder.ApiBuilder.path;

public class WorkspaceBindings implements Plugin {
  private final JWTService jwtService;
  private final WorkspaceService workspaceService;

  public WorkspaceBindings(final JWTService jwtService, final WorkspaceService workspaceService) {
    this.jwtService = jwtService;
    this.workspaceService = workspaceService;
  }

  private record PathInformation(int workspaceId, Path filePath) {
    static PathInformation of(Context context) {
      final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));
      final var filePath = Path.of(context.pathParam("filePath"));

      return new PathInformation(workspaceId, filePath);
    }

    String fileName() {
      return filePath.getFileName().toString();
    }
  }

  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      //before("/ws/*", authorize); <- dont want to force auth on the health check.
      // Health check
      path("/health", () -> ApiBuilder.get(ctx -> ctx.status(200)));

      // CRUD operations for Files and Directories:
      path("/ws/{workspaceId}/<filePath>",
           () -> {
             ApiBuilder.get(this::get);
             ApiBuilder.put(this::put);
             ApiBuilder.delete(this::delete);
             ApiBuilder.post(this::post);
           });

      // CRD operations for Workspaces
      path("/ws/{workspaceId}", () -> {
        ApiBuilder.get(this::listContents);
        ApiBuilder.delete(this::deleteWorkspace);
      });
      path("/ws/create", () -> ApiBuilder.post(this::createWorkspace));
    });

    // Default exception handlers for common endpoint exceptions
    javalin.exception(NoSuchWorkspaceException.class, (ex, ctx) -> ctx.status(404).result(ex.getMessage()));
    javalin.exception(IOException.class, (ex, ctx) -> ctx.status(500).result(ex.getMessage()));
  }

  /**
   * Validate that the request has a valid authorization
   */
  private JWTService.UserSession authorize(Context context) {
    final var authHeader = context.header("Authorization");
    final var activeRole = context.header("x-hasura-role");
    try{
      return jwtService.validateAuthorization(authHeader, activeRole);
    } catch (JWTVerificationException jve) {
      context.status(401);
      throw new UnauthorizedResponse();
    }
  }

  private void createWorkspace(Context context) {
    final String helpText = """
        {
            "workspaceLocation": text     // Name of the folder the workspace will live in
            "parcelId": number            // Id of the workspace's parcel
            "workspaceName": text?        // Optional. If provided, the workspace will be called the specified value (defaults to the value of "workspaceLocation")
        }
        """;
    final Path workspaceLocation;
    final String workspaceName;
    final int parcelId;
    final var user = authorize(context);

    try(final var reader = Json.createReader(new StringReader(context.body()))) {
      final var bodyJson = reader.readObject();

      parcelId = bodyJson.getInt("parcelId");
      final var workspaceString = bodyJson.getString("workspaceLocation");
      if(workspaceString.contains("/")){
        context.status(400).result("Workspace location may not contain '/'");
        return;
      }

      workspaceLocation = Path.of(bodyJson.getString("workspaceLocation"));
      workspaceName = bodyJson.containsKey("workspaceName") ? bodyJson.getString("workspaceName") : workspaceLocation.toString();
    } catch (NullPointerException npe) {
      context.status(400).result("Mandatory body parameter is null. Request body format is:\n" + helpText);
      return;
    } catch (JsonException je) {
      context.status(400).result("Request body is malformed. Request body format is:\n" + helpText);
      return;
    }

    final Optional<Integer> workspaceId = workspaceService.createWorkspace(
        workspaceLocation,
        workspaceName,
        user.userId(),
        parcelId);
    if(workspaceId.isPresent()) {
      context.status(200).result(workspaceId.get().toString());
    } else {
      context.status(500).result("Unable to create workspace.");
    }
  }

  private void deleteWorkspace(Context context) {
    final int workspaceId  = Integer.parseInt(context.pathParam("workspaceId"));

    try {
      if (workspaceService.deleteWorkspace(workspaceId)) {
        context.status(200).result("Workspace deleted.");
      } else {
        context.status(500).result("Unable to delete workspace.");
      }
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    } catch (SQLException e) {
      context.status(500).result("Unable to delete workspace. " +e.getMessage());
    }
  }

  private void listContents(Context context) {
    final var workspaceId = Integer.parseInt(context.pathParam("workspaceId"));

    final Optional<Path> directoryPath;
    if(context.pathParamMap().containsKey("filePath")) {
      directoryPath = Optional.of(Path.of(context.pathParam("filePath")));
    } else {
      directoryPath = Optional.empty();
    }

    // Query params
    final var depthString = context.queryParam("depth");
    final int depth = depthString != null ? Integer.parseInt(depthString) : -1;

    try {
      final var fileTree = workspaceService.listFiles(workspaceId, directoryPath, depth);

      if (fileTree == null) {
        context.status(404).result("No such directory.");
        return;
      }

      context.status(200).json(fileTree.toJson().toString());
    } catch (SQLException e) {
      context.status(500).result(e.getMessage());
    } catch (NoSuchWorkspaceException ex) {
      context.status(404).result(ex.getMessage());
    }
  }

  private void get(Context context) throws NoSuchWorkspaceException {
    final var pathInfo = PathInformation.of(context);

    if (workspaceService.isDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
      listContents(context);
    } else {
      if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(404).result("No such file exists in the workspace: " + pathInfo.filePath);
        return;
      }

      try {
        final var fileStream = workspaceService.loadFile(pathInfo.workspaceId, pathInfo.filePath());
        final var fileReader = new BufferedInputStream(fileStream.readingStream());
        context.header("x-render-type", workspaceService.getFileType(pathInfo.filePath).name());
        context.contentType(ContentType.OCTET_STREAM);
        context.header("Content-Disposition", "attachment; filename=\"" + pathInfo.fileName() + "\"");
        context.header("Content-Length", "" + fileStream.fileSize());
        context.status(200).result(fileReader);
      } catch (IOException | SQLException e) {
        context.status(500).result("Could not load file " + pathInfo.fileName());
      }
    }
  }

  private void put(Context context) throws NoSuchWorkspaceException, IOException {
    final var pathInfo = PathInformation.of(context);
    final String type;
    final boolean overwrite;

    // Validate the permitted query parameters on Put requests
    try {
      type = context.queryParamAsClass("type", String.class)
                    .allowNullable()
                    .check(Objects::nonNull, "'type' must be provided.")
                    .check(ts -> ts == null || ts.equalsIgnoreCase("file") || ts.equalsIgnoreCase("directory"),
                           "'type' must be one of 'file' or 'directory'")
                    .get();
      overwrite = context.queryParamAsClass("overwrite", Boolean.class).getOrDefault(false);
    } catch (ValidationException ve) {
      context.status(400).result(ve.getMessage() != null ? ve.getMessage() : "Invalid request");
      return;
    }

    // Report a "Conflict" status if the file already exists, is a file and not a directory, and "overwrite" is false
    if(workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)
       && !workspaceService.isDirectory(pathInfo.workspaceId, pathInfo.filePath)
       && !overwrite) {
      context.status(409).result(pathInfo.fileName() + " already exists.");
      return;
    }

    if ("file".equalsIgnoreCase(type)) {
      // Reject the request if the file isn't provided.
      final var file = context.uploadedFile("file");
      if (file == null) {
        context.status(400).result("No file provided with the name " + pathInfo.fileName());
        return;
      }

      if (workspaceService.saveFile(pathInfo.workspaceId, pathInfo.filePath, file)) {
        context.status(200).result("File " + pathInfo.fileName() + " uploaded to " + pathInfo.filePath);
      } else {
        context.status(500).result("Could not save file.");
      }
    } else if ("directory".equalsIgnoreCase(type)) {
      if (workspaceService.createDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Directory created.");
      } else {
        context.status(500).result("Could not create directory.");
      }
    } else {
      context.status(400).result("Query param 'type' has invalid value "+type);
    }
  }

  private void post(Context context) throws IOException, SQLException, NoSuchWorkspaceException {

    try (final var bodyReader = Json.createReader(new StringReader(context.body()))) {
      final var bodyJson = bodyReader.readObject();

      if (bodyJson.containsKey("moveTo")) {
        handleMove(context, bodyJson);
      } else if (bodyJson.containsKey("copyTo")) {
        handleCopy(context, bodyJson);
      } else {
        throw new IllegalArgumentException("Unsupported operation. Expected 'moveTo' or 'copyTo'.");
      }

      context.status(200).result("Success");
    } catch (Exception e) {
      e.printStackTrace();
      context.status(400).result("Error: " + e.getMessage() != null ? e.getMessage() : "Invalid request");
    }
  }

  private boolean isCopyOrMoveValid(Context context, int sourceWorkspace, Path sourceFile, int targetWorkspace, Path targetFile)
  throws NoSuchWorkspaceException {
    // Reject if source does not exist
    if (!workspaceService.checkFileExists(sourceWorkspace, sourceFile)) {
      context.status(404).result(sourceFile + " does not exist in the source workspace.");
      return false;
    }

    // Reject if target workspace does not exist
    if (!workspaceService.checkFileExists(targetWorkspace, Path.of("/"))) {
      context.status(404).result("Target workspace with ID "+targetWorkspace+" does not exist.");
      return false;
    }

    // Return "Conflicted" if destination exists
    if (workspaceService.checkFileExists(targetWorkspace, targetFile)) {
      context.status(409).result(targetFile + " already exists");
      return false;
    }

    return true;
  }

  private void handleMove(Context context, JsonObject bodyJson)
  throws IOException, NoSuchWorkspaceException, SQLException {
    final var pathInfo = PathInformation.of(context);

    final var destination = Path.of(bodyJson.getString("moveTo"));
    int sourceWorkspace = pathInfo.workspaceId;
    int targetWorkspace = pathInfo.workspaceId;  // default to same workspace unless toWorkspace is included
    if (bodyJson.containsKey("toWorkspace")) {
      targetWorkspace = bodyJson.getInt("toWorkspace");
    }

    boolean validMove = isCopyOrMoveValid(context, sourceWorkspace, pathInfo.filePath, targetWorkspace, destination);
    if (!validMove) return;

    if (workspaceService.isDirectory(sourceWorkspace, pathInfo.filePath())) {
      try {
        if (workspaceService.moveDirectory(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          context.status(200);
        } else {
          context.status(500).result("Unable to move directory.");
        }
      } catch (NoSuchWorkspaceException ex) {
        context.status(404).result(ex.getMessage());
      } catch (SQLException | WorkspaceFileOpException e) {
        context.status(500).result("Unable to move directory: " + e.getMessage());
      }
    } else {
      try {
        if (workspaceService.moveFile(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          context.status(200);
        } else {
          context.status(500).result("Unable to move file.");
        }
      } catch (NoSuchWorkspaceException ex) {
        context.status(404).result(ex.getMessage());
      } catch (SQLException | WorkspaceFileOpException e) {
        context.status(500).result("Unable to move file. " + e.getMessage());
      }
    }
  }

  private void handleCopy(Context context, JsonObject bodyJson)
  throws NoSuchWorkspaceException, SQLException {
    final var pathInfo = PathInformation.of(context);

    final var destination = Path.of(bodyJson.getString("copyTo"));
    int sourceWorkspace = pathInfo.workspaceId;
    int targetWorkspace = pathInfo.workspaceId; // default to same workspace unless toWorkspace is included
    if (bodyJson.containsKey("toWorkspace")) {
      targetWorkspace = bodyJson.getInt("toWorkspace");
    }

    boolean validCopy = isCopyOrMoveValid(context, sourceWorkspace, pathInfo.filePath, targetWorkspace, destination);
    if (!validCopy) return;

    if (workspaceService.isDirectory(sourceWorkspace, pathInfo.filePath())) {
      try {
        if (workspaceService.copyDirectory(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          context.status(200);
        } else {
          context.status(500).result("Unable to copy directory.");
        }
      } catch (NoSuchWorkspaceException ex) {
        context.status(404).result(ex.getMessage());
      } catch (SQLException | WorkspaceFileOpException e) {
        context.status(500).result("Unable to copy directory: " + e.getMessage());
      }
    } else {
      try {
        if (workspaceService.copyFile(sourceWorkspace, pathInfo.filePath, targetWorkspace, destination)) {
          context.status(200);
        } else {
          context.status(500).result("Unable to copy file.");
        }
      } catch (NoSuchWorkspaceException ex) {
        context.status(404).result(ex.getMessage());
      } catch (SQLException | WorkspaceFileOpException e) {
        context.status(500).result("Unable to copy file: " + e.getMessage());
      }
    }
  }


  private void delete(Context context) throws NoSuchWorkspaceException, IOException {
    final var pathInfo = PathInformation.of(context);

    if (!workspaceService.checkFileExists(pathInfo.workspaceId, pathInfo.filePath)) {
      context.status(404).result(pathInfo.fileName() + " does not exist.");
      return;
    }

    if (workspaceService.isDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
      if (workspaceService.deleteDirectory(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("Directory deleted.");
      } else {
        context.status(500).result("Could not delete directory.");
      }
    } else {
      if (workspaceService.deleteFile(pathInfo.workspaceId, pathInfo.filePath)) {
        context.status(200).result("File deleted.");
      } else {
        context.status(500).result("Could not delete file.");
      }
    }
  }
}
