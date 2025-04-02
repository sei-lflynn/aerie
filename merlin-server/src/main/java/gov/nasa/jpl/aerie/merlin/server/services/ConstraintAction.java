package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.*;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.*;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ConstraintRunRecord;
import gov.nasa.jpl.aerie.types.MissionModelId;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class ConstraintAction {
  private final ConstraintsDSLCompilationService constraintsDSLCompilationService;
  private final ConstraintService constraintService;
  private final PlanService planService;
  private final SimulationService simulationService;

  public ConstraintAction(
      final ConstraintsDSLCompilationService constraintsDSLCompilationService,
      final ConstraintService constraintService,
      final PlanService planService,
      final SimulationService simulationService
  ) {
    this.constraintsDSLCompilationService = constraintsDSLCompilationService;
    this.constraintService = constraintService;
    this.planService = planService;
    this.simulationService = simulationService;
  }

  /**
   * Update the parameter schema of a procedural constraint's definition
   * @param constraintId The id of the constraint's metadata
   * @param revision The definition to be updated
   */
  public void refreshConstraintProcedureParameterTypes(long constraintId, long revision) {
    constraintService.refreshConstraintProcedureParameterTypes(constraintId, revision);
  }

  /**
   * Check the constraints on a plan's specification for violations.
   *
   * @param planId The plan to check.
   * @param simulationDatasetId If provided, the id of the simulation dataset to check constraints against.
   * Defaults to the latest simulation of the plan
   * @param force If true, ignore cached values and rerun all constraints.
   * @param userSession The Hasura Session that made the request.
   * @return A mapping of each constraint and its result.
   * @throws NoSuchPlanException If the plan does not exist.
   * @throws MissionModelService.NoSuchMissionModelException If the plan's mission model does not exist.
   * @throws SimulationDatasetMismatchException If the specified simulation is not a simulation of the specified plan.
   */
  public Map<ConstraintRecord, Fallible<ConstraintResult, ?>> getViolations(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final boolean force
  ) throws NoSuchPlanException, MissionModelService.NoSuchMissionModelException, SimulationDatasetMismatchException {
    final var plan = this.planService.getPlanForValidation(planId);

    // Get a Handle for the Simulation Results
    final SimulationResultsHandle resultsHandle;
    if (simulationDatasetId.isPresent()) {
      resultsHandle = this.simulationService.get(planId, simulationDatasetId.get())
                                            .orElseThrow(() -> new InputMismatchException(
                                                "simulation dataset with id `"
                                                + simulationDatasetId.get().id()
                                                + "` does not exist"));
    } else {
      final var revisionData = this.planService.getPlanRevisionData(planId);
      resultsHandle = this.simulationService.get(planId, revisionData)
                                            .orElseThrow(() -> new InputMismatchException(
                                                "plan with id "
                                                + planId.id()
                                                + " has not yet been simulated at its current revision"));
    }

    final SimulationDatasetId simDatasetId = resultsHandle.getSimulationDatasetId();

    final var constraints = new HashMap<>(this.planService.getConstraintsForPlan(planId));

    final var constraintResultMap = new HashMap<ConstraintRecord, Fallible<ConstraintResult, ?>>();

    // temp disable cache loading
    final var validConstraintRuns = new HashMap<Long, ConstraintRunRecord>();
    //this.constraintService.getValidConstraintRuns(constraints, simDatasetId);

    // Remove any constraints that we've already checked, so they aren't rechecked.
    for (ConstraintRunRecord constraintRun : validConstraintRuns.values()) {
        constraintResultMap.put(constraints.remove(constraintRun.constraintId()), Fallible.of(constraintRun.result()));
    }

    // If the lengths don't match we need check the left-over constraints.
    if (!constraints.isEmpty()) {
      final var simStartTime = resultsHandle.startTime();
      final var simDuration = resultsHandle.duration();
      final var simOffset = plan.simulationOffset();

      final var activities = new ArrayList<ActivityInstance>();
      final var simulatedActivities = resultsHandle.getSimulatedActivities();
      for (final var entry : simulatedActivities.entrySet()) {
        final var id = entry.getKey();
        final var activity = entry.getValue();

        final var activityOffset = Duration.of(
            simStartTime.until(activity.start(), ChronoUnit.MICROS),
            Duration.MICROSECONDS);

        activities.add(new ActivityInstance(
            id.id(),
            activity.type(),
            activity.arguments(),
            Interval.between(activityOffset, activityOffset.plus(activity.duration()))));
      }

      final var externalDatasets = this.planService.getExternalDatasets(planId, simDatasetId);
      final var realExternalProfiles = new HashMap<String, LinearProfile>();
      final var discreteExternalProfiles = new HashMap<String, DiscreteProfile>();

      for (final var pair : externalDatasets) {
        final var offsetFromSimulationStart = pair.getLeft().minus(simOffset);
        final var profileSet = pair.getRight();

        for (final var profile : profileSet.discreteProfiles().entrySet()) {
          discreteExternalProfiles.put(
              profile.getKey(),
              DiscreteProfile.fromExternalProfile(
                  offsetFromSimulationStart,
                  profile.getValue().segments()));
        }
        for (final var profile : profileSet.realProfiles().entrySet()) {
          realExternalProfiles.put(
              profile.getKey(),
              LinearProfile.fromExternalProfile(
                  offsetFromSimulationStart,
                  profile.getValue().segments()));
        }
      }

      final var environment = new EvaluationEnvironment(realExternalProfiles, discreteExternalProfiles);

      final var realProfiles = new HashMap<String, LinearProfile>();
      final var discreteProfiles = new HashMap<String, DiscreteProfile>();

      // try to compile and run the constraint that were not
      // successful and cached in the past


      //compile
      final var compiledConstraints = new ArrayList<ExecutableConstraint>();
      for (final var entry : constraints.entrySet()) {
        final var constraint = entry.getValue();

        switch (constraint.type()) {
          case ConstraintType.EDSL e -> {
            final var compilationResult = tryCompileEDSLConstraint(
                plan.missionModelId(),
                planId,
                simDatasetId,
                constraint);

            if (compilationResult.isFailure()) {
              final Fallible<ConstraintResult, ?> r = Fallible.failure(compilationResult.getFailure(), compilationResult.getMessage());
              constraintResultMap.put(constraint, r);
              continue;
            }

            compiledConstraints.add(new ExecutableConstraint.EDSLConstraint(constraint, compilationResult.getOrNull()));

            /*
            final Expression<ConstraintResult> expression = compilationResult.getOrNull();

            // Get the expression out of the result
            final var r = compilationResult.getOrNull();
            if(r == null) {
              constraintResultMap.put(constraint, Fallible.failure(
                  new Error("Expect compiled constraint code. Received null value.")));
              continue;
            }
            */

          }
          case ConstraintType.JAR j -> {
            compiledConstraints.add(new ExecutableConstraint.JARConstraint(constraint));
          }
        }
      }

      // sort constraints
      Collections.sort(compiledConstraints);

      // run constraints
      for(final var constraint : compiledConstraints) {
        final var record = constraint.record();
        switch (constraint) {
          case ExecutableConstraint.EDSLConstraint edsl: {
            // Cache resources that haven't yet been used by prior constraints
            final var resources = edsl.cacheResources(realProfiles, discreteProfiles, resultsHandle);
            final Interval bounds = Interval.between(Duration.ZERO, simDuration);
            final var preparedResults = new SimulationResults(
                simStartTime,
                bounds,
                activities,
                realProfiles,
                discreteProfiles);

            constraintResultMap.put(record, Fallible.of(edsl.run(preparedResults, environment, resources)));
            break;
          }
          case ExecutableConstraint.JARConstraint jar: {

            break;
          }
        }
      }
      // Filter for constraints that were compiled and ran with results
      // convert these successful failables to ConstraintResults
      final var compiledConstraintMap = constraintResultMap.entrySet().stream()
                                                           .filter(set -> {
                                                             Fallible<ConstraintResult, ?> fallible = set.getValue();
                                                             return !fallible.isFailure() && (fallible
                                                                                                  .getOptional()
                                                                                                  .isPresent());
                                                           })
                                                           .collect(Collectors.toMap(
                                                               entry -> entry.getKey().priority(),
                                                               set -> set
                                                                   .getValue()
                                                                   .getOptional()
                                                                   .get()));

      // Use the constraints that were compiled and ran with results
      // to filter out the constraintCode map to match
      final var compileConstraintCode =
          constraints.entrySet().stream().filter(set -> compiledConstraintMap.containsKey(set.getKey())).collect(
              Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      // Only update the db when constraints were compiled and ran with results.
      /* temp disable caching results in db
      constraintService.createConstraintRuns(
          compileConstraintCode,
          compiledConstraintMap,
          simDatasetId);
       */
    }

    return constraintResultMap;
  }

  /**
   * Attempt to compile an EDSL Constraint.
   * @param modelId The mission model id to get activity and resource types from.
   * @param planId The plan id to get external resource types from.
   * @param simDatasetId The simulation dataset id to filter external resource types on.
   * @param constraint The constraint to be compiled.
   * @return
   *    On success, return a {@code Fallible<Expression<EDSLConstraintResult>>} containing the compiled constraint code
   *      in a form that can be evaluated against simulation results.
   *    On failure, return a {@code Fallible<Error>} in the Failure state containing the compilation error.
   */
  private Fallible<Expression<EDSLConstraintResult>, ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error> tryCompileEDSLConstraint(
      MissionModelId modelId,
      PlanId planId,
      SimulationDatasetId simDatasetId,
      ConstraintRecord constraint
  ) {
    final ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult constraintCompilationResult;
    try {
      constraintCompilationResult = constraintsDSLCompilationService.compileConstraintsDSL(
          modelId,
          Optional.of(planId),
          Optional.of(simDatasetId),
          ((ConstraintType.EDSL) constraint.type()).definition()
      );
    } catch (MissionModelService.NoSuchMissionModelException | NoSuchPlanException ex) {
      return Fallible.failure(
          new ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error(
             List.of(
                 new ConstraintsCompilationError.UserCodeError(
                     ex.getMessage(),
                     ex.toString(),
                     new ConstraintsCompilationError.CodeLocation(0,0),
                     ex.toString()))));
    }

    // Try to compile the constraint and capture failures
    if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Success success) {
      return Fallible.of(success.constraintExpression());
    } else if (constraintCompilationResult instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error error) {
      return Fallible.failure(error, "Constraint '" + constraint.name() + "' compilation failed:\n ");
    } else {
      return Fallible.failure(
          new ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error(List.of(
              new ConstraintsCompilationError.UserCodeError(
                  "Unhandled variant of ConstraintsDSLCompilationResult: " + constraintCompilationResult, "",
                  new ConstraintsCompilationError.CodeLocation(0, 0),
                  ""))));
    }
  }
}
