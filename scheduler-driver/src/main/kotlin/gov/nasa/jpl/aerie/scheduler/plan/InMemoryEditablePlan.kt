package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.jpl.aerie.merlin.driver.MissionModel
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.ammos.aerie.procedural.scheduling.utils.EasyEditablePlanDriver
import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator
import gov.nasa.jpl.aerie.scheduler.model.*
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

/*
 * An implementation of [EditablePlan] that stores the plan in memory for use in the internal scheduler.
 *

 */
data class InMemoryEditablePlan(
  private val missionModel: MissionModel<*>,
  private var idGenerator: DirectiveIdGenerator,
  private val plan: SchedulerToProcedurePlanAdapter,
  private val simulationFacade: SimulationFacade,
  private val lookupActivityType: (String) -> ActivityType
) : EasyEditablePlanDriver(plan) {

  override fun generateDirectiveId(): ActivityDirectiveId = idGenerator.next()
  override fun latestResultsInternal(): PerishableSimulationResults? {
    val merlinResults = simulationFacade.latestSimulationData.getOrNull() ?: return null
    return MerlinToProcedureSimulationResultsAdapter(merlinResults.driverResults, plan.copy(schedulerPlan = plan.duplicate()))
  }

  override fun createInternal(directive: Directive<AnyDirective>) {
    plan.add(directive.toSchedulingActivity(lookupActivityType, true))
  }

  override fun deleteInternal(id: ActivityDirectiveId) {
    plan.remove(plan.activitiesById[id])
  }

  override fun simulateInternal(options: SimulateOptions) {
    simulationFacade.simulateWithResults(plan, options.pause.resolve(this))
  }

  override fun validateArguments(directive: Directive<AnyDirective>) {
    lookupActivityType(directive.type).specType.inputType.validateArguments(directive.inner.arguments)
  }

  companion object {
    @JvmStatic fun Directive<AnyDirective>.toSchedulingActivity(lookupActivityType: (String) -> ActivityType, isNew: Boolean) = SchedulingActivity(
        id,
        lookupActivityType(type),
        when (val s = start) {
          is DirectiveStart.Absolute -> s.time
          is DirectiveStart.Anchor -> s.offset
        },
        when (val d = lookupActivityType(type).durationType) {
          is DurationType.Controllable -> {
            inner.arguments[d.parameterName]?.asInt()?.let { Duration(it.get()) }
          }
          is DurationType.Parametric -> {
            d.durationFunction.apply(inner.arguments)
          }
          is DurationType.Fixed -> {
            d.duration
          }
          else -> Duration.ZERO
        },
        inner.arguments,
        null,
        when (val s = start) {
          is DirectiveStart.Absolute -> null
          is DirectiveStart.Anchor -> s.parentId
        },
      when (val s = start) {
        is DirectiveStart.Absolute -> true
        is DirectiveStart.Anchor -> s.anchorPoint == DirectiveStart.Anchor.AnchorPoint.Start
      },
      isNew,
      name
    )
  }
}
