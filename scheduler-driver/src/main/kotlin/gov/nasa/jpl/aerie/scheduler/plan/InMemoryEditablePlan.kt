package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.jpl.aerie.merlin.driver.MissionModel
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan
import gov.nasa.ammos.aerie.procedural.scheduling.plan.NewDirective
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator
import gov.nasa.jpl.aerie.scheduler.model.*
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults as TimelineSimResults

data class InMemoryEditablePlan(
    private val missionModel: MissionModel<*>,
    private var idGenerator: DirectiveIdGenerator,
    private val plan: SchedulerToProcedurePlanAdapter,
    private val simulationFacade: SimulationFacade,
    private val lookupActivityType: (String) -> ActivityType
) : EditablePlan, Plan by plan {

  private val commits = mutableListOf<Commit>()
  var uncommittedChanges = mutableListOf<Edit>()
    private set

  val totalDiff: List<Edit>
    get() = commits.flatMap { it.diff }

  private var simResultsUpToDate: MutableSet<WeakReference<MerlinToProcedureSimulationResultsAdapter>> = mutableSetOf()

  override fun latestResults(): SimulationResults? {
    val merlinResults = simulationFacade.latestSimulationData.getOrNull() ?: return null

    // kotlin checks structural equality by default, not referential equality.
    val isStale = merlinResults.plan.activities != plan.activities

    val results = MerlinToProcedureSimulationResultsAdapter(merlinResults.driverResults, isStale, plan)
    if (!isStale) simResultsUpToDate.add(WeakReference(results))
    return results
  }

  override fun create(directive: NewDirective): ActivityDirectiveId {
    class ParentSearchException(id: ActivityDirectiveId, size: Int): Exception("Expected one parent activity with id $id, found $size")
    val id = idGenerator.next()
    val parent = when (val s = directive.start) {
      is DirectiveStart.Anchor -> {
        val parentList = directives()
            .filter { it.id == s.parentId }
            .collect(totalBounds())
        if (parentList.size != 1) throw ParentSearchException(s.parentId, parentList.size)
        parentList.first()
      }
      is DirectiveStart.Absolute -> null
    }
    val resolved = directive.resolve(id, parent)
    uncommittedChanges.add(Edit.Create(resolved))
    resolved.validateArguments(lookupActivityType)
    plan.add(resolved.toSchedulingActivity(lookupActivityType, true))

    for (simResults in simResultsUpToDate) {
      simResults.get()?.stale = true
    }
    // create a new list instead of `.clear` because commit objects have the same reference
    simResultsUpToDate = mutableSetOf()

    return id
  }

  override fun commit() {
    if (uncommittedChanges.isEmpty()) return
    val committedEdits = uncommittedChanges
    uncommittedChanges = mutableListOf()
    commits.add(Commit(committedEdits, simResultsUpToDate))
  }

  override fun rollback(): List<Edit> {
    // Early return if there are no changes, to keep staleness accuracy
    if (uncommittedChanges.isEmpty()) return emptyList()

    val result = uncommittedChanges
    uncommittedChanges = mutableListOf()
    for (edit in result) {
      when (edit) {
        is Edit.Create -> {
          plan.remove(edit.directive.toSchedulingActivity(lookupActivityType, true))
        }
      }
    }
    for (simResult in simResultsUpToDate) {
      simResult.get()?.stale = true
    }
    for (simResult in commits.last().simResultsUpToDate) {
      simResult.get()?.stale = false
    }
    simResultsUpToDate = commits.last().simResultsUpToDate
    return result
  }

  override fun simulate(options: SimulateOptions): TimelineSimResults {
    simulationFacade.simulateWithResults(plan, options.pause.resolve(this))
    return latestResults()!!
  }

  // These cannot be implemented with the by keyword,
  // because directives() below needs a custom implementation.
  override fun totalBounds() = plan.totalBounds()
  override fun toRelative(abs: Instant) = plan.toRelative(abs)
  override fun toAbsolute(rel: Duration) = plan.toAbsolute(rel)

  companion object {
    fun Directive<AnyDirective>.validateArguments(lookupActivityType: (String) -> ActivityType) {
      lookupActivityType(type).specType.inputType.validateArguments(inner.arguments)
    }

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
