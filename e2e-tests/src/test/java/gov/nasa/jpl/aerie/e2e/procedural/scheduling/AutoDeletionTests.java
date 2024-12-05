package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoDeletionTests extends ProceduralSchedulingSetup {
  private GoalInvocationId edslId;
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final String coexGoalDefinition =
          """
          export default function myGoal() {
            return Goal.CoexistenceGoal({
              forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
              activityTemplate: ActivityTemplates.GrowBanana({quantity: 1, growingDuration: Temporal.Duration.from({minutes:1})}),
              startsAt:TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
            })
          }""";

      edslId = hasura.createSchedulingSpecGoal(
          "Coexistence Scheduling Test Goal",
          coexGoalDefinition,
          "",
          specId,
          0,
          false
      );

      int procedureJarId = gateway.uploadJarFile("build/libs/ActivityAutoDeletionGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          1,
          false
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(procedureId.goalId());
    hasura.deleteSchedulingGoal(edslId.goalId());
  }

  @Test
  void createsOneActivityIfRunOnce() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(1, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana")
    ));
  }

  @Test
  void createsTwoActivitiesSteadyState_JustBefore() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    for (int i = 0; i < 3; i++) {
      hasura.updatePlanRevisionSchedulingSpec(planId);
      hasura.awaitScheduling(specId);

      final var plan = hasura.getPlan(planId);
      final var activities = plan.activityDirectives();

      assertEquals(2, activities.size());

      assertTrue(activities.stream().anyMatch(
          it -> Objects.equals(it.type(), "BiteBanana")
      ));

      assertTrue(activities.stream().anyMatch(
          it -> Objects.equals(it.type(), "GrowBanana")
      ));
    }
  }

  @Test
  void createsOneActivitySteadyState_AtBeginning() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", true)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    for (int i = 0; i < 3; i++) {
      hasura.updatePlanRevisionSchedulingSpec(planId);
      hasura.awaitScheduling(specId);

      final var plan = hasura.getPlan(planId);
      final var activities = plan.activityDirectives();

      assertEquals(1, activities.size());

      assertTrue(activities.stream().anyMatch(
          it -> Objects.equals(it.type(), "BiteBanana")
      ));
    }
  }
}
