package gov.nasa.ammos.aerie.procedural.scheduling.utils

import gov.nasa.ammos.aerie.procedural.scheduling.plan.*
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults
import gov.nasa.jpl.aerie.types.ActivityDirectiveId
import java.lang.ref.WeakReference

/**
 * A default (but optional) driver for [EditablePlan] implementations that handles
 * commits/rollbacks, staleness checking, and anchor deletion automatically.
 *
 * The [EditablePlan] interface requires the implementor to perform some fairly complex
 * stateful operations, with a tangle of interdependent algorithmic guarantees.
 * Most of those operations are standard among all implementations though, so this driver
 * captures most of it in a reusable form. Just inherit from this class to make a valid
 * [EditablePlan].
 *
 * The subclass is still responsible for simulation and the basic context-free creation
 * and deletion operations. See the *Contracts* section of each abstract method's doc comment.
 */
/*
 * ## Staleness checking
 *
 * The editable plan instance keeps track of sim results that it has produced using weak references, and can dynamically
 * update their staleness if the plan is changed after it was simulated. The process is this:
 *
 * 1. [InMemoryEditablePlan] has a set of weak references to simulation results objects that are currently up-to-date.
 *    I used weak references because if the user can't access it anymore, staleness doesn't matter and we might as well
 *    let it get gc'ed.
 * 2. When the user gets simulation results, either through simulation or by getting the latest, it always checks for
 *    plan equality between the returned results and the current plan, even if we just simulated. If it is up-to-date, a
 *    weak ref is added to the set.
 * 3. When an edit is made, the sim results in the current set are marked stale; then the set is reset to new reference
 *    to an empty set.
 * 4. When a commit is made, the commit object takes *shared ownership* of the set. If a new simulation is run (step 2)
 *    the plan can still add to the set while it is still jointly owned by the commit. Then when an edit is made (step 3)
 *    the commit will become the sole owner of the set.
 * 5. When changes are rolled back, any sim results currently in the plan's set are marked stale, the previous commit's
 *    sim results are marked not stale, then the plan will resume joint ownership of the previous commit's set.
 *
 * The joint ownership freaks me out a wee bit, but I think it's safe because the commits are only used to keep the
 * previous sets from getting gc'ed in the event of a rollback. Only the plan object actually mutates the set.
 */
abstract class EasyEditablePlanDriver(
  private val plan: Plan
): EditablePlan, Plan by plan {
  /**
   * Create a unique directive ID.
   *
   * *Contract:*
   * - the implementor must return an ID that is distinct from any activity ID that was in the initial plan
   *   or that has been returned from this method before during the implementor's lifetime.
   */
  protected abstract fun generateDirectiveId(): ActivityDirectiveId

  /**
   * Create a directive in the plan.
   *
   * *Contracts*:
   * - the driver will guarantee that the directive ID does not collide with any other directive currently in the plan.
   * - the implementor must return the new directive in future calls to [Plan.directives], unless it is later deleted.
   * - the implementor must include the directive in future input plans for simulation, unless it is later deleted.
   */
  protected abstract fun createInternal(directive: Directive<AnyDirective>)

  /**
   * Remove a directive from the plan, specified by ID.
   */
  protected abstract fun deleteInternal(id: ActivityDirectiveId)

  /**
   * Get the latest simulation results.
   *
   * *Contract:*
   * - the implementor must return equivalent results objects if this method is called multiple times without
   *   updates.
   *
   * The implementor doesn't have to return the exact same *instance* each time if no updates are made (i.e. referential
   * equality isn't required, only structural equality).
   */
  protected abstract fun latestResultsInternal(): PerishableSimulationResults?

  /**
   * Simulate the current plan.
   *
   * *Contracts:*
   * - all prior creations and deletions must be reflected in the simulation run.
   * - the results corresponding to this run must be returned from future calls to [latestResultsInternal]
   *   until the next time [simulateInternal] is called.
   */
  protected abstract fun simulateInternal(options: SimulateOptions)

  /**
   * Optional validation hook for new activities. The default implementation does nothing.
   *
   * Implementor should throw if the arguments are invalid.
   */
  protected open fun validateArguments(directive: Directive<AnyDirective>) {}

  private data class Commit(
    val diff: Set<Edit>,

    /**
     * A record of the simulation results objects that were up-to-date when the commit
     * was created.
     *
     * This has SHARED OWNERSHIP with [EasyEditablePlanDriver]; the editable plan may add more to
     * this list AFTER the commit is created.
     */
    val upToDateSimResultsSet: MutableSet<WeakReference<PerishableSimulationResults>>
  )

  private var committedChanges = Commit(setOf(), mutableSetOf())
  var uncommittedChanges = mutableListOf<Edit>()

  val totalDiff: Set<Edit>
    get() = committedChanges.diff

  // Jointly owned set of up-to-date simulation results. See class-level comment for algorithm explanation.
  private var upToDateSimResultsSet: MutableSet<WeakReference<PerishableSimulationResults>> = mutableSetOf()

  override fun latestResults(): SimulationResults? {
    val internalResults = latestResultsInternal()

    // kotlin checks structural equality by default, not referential equality.
    val isStale = internalResults?.inputDirectives()?.toSet() != directives().toSet()

    internalResults?.setStale(isStale)

    if (!isStale) upToDateSimResultsSet.add(WeakReference(internalResults))
    return internalResults
  }

  override fun create(directive: NewDirective): ActivityDirectiveId {
    class ParentSearchException(id: ActivityDirectiveId, size: Int): Exception("Expected one parent activity with id $id, found $size")
    val id = generateDirectiveId()
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

    validateArguments(resolved)

    createInternal(resolved)

    for (simResults in upToDateSimResultsSet) {
      simResults.get()?.setStale(true)
    }
    // create a new list instead of `.clear` because commit objects have the same reference
    upToDateSimResultsSet = mutableSetOf()

    return id
  }

  override fun delete(directive: Directive<AnyDirective>, strategy: DeletedAnchorStrategy) {
    val directives = directives().cache()


    val directivesToDelete: Set<Directive<AnyDirective>>
    val directivesToCreate: Set<Directive<AnyDirective>>

    if (strategy == DeletedAnchorStrategy.Cascade) {
      directivesToDelete = deleteCascadeRecursive(directive, directives).toSet()
      directivesToCreate = mutableSetOf()
    } else {
      directivesToDelete = mutableSetOf(directive)
      directivesToCreate = mutableSetOf()
      for (d in directives) {
        when (val childStart = d.start) {
          is DirectiveStart.Anchor -> {
            if (childStart.parentId == directive.id) {
              when (strategy) {
                DeletedAnchorStrategy.Error -> throw Exception("Cannot delete an activity that has anchors pointing to it without a ${DeletedAnchorStrategy::class.java.simpleName}")
                DeletedAnchorStrategy.ReAnchor -> {
                  directivesToDelete.add(d)
                  val start = when (val parentStart = directive.start) {
                    is DirectiveStart.Absolute -> DirectiveStart.Absolute(parentStart.time + childStart.offset)
                    is DirectiveStart.Anchor -> DirectiveStart.Anchor(
                      parentStart.parentId,
                      parentStart.offset + childStart.offset,
                      parentStart.anchorPoint,
                      childStart.estimatedStart
                    )
                  }
                  directivesToCreate.add(d.copy(start = start))
                }
                else -> throw Error("internal error; unreachable")
              }
            }
          }
          else -> {}
        }
      }
    }

    for (d in directivesToDelete) {
      uncommittedChanges.add(Edit.Delete(d))
      deleteInternal(d.id)
    }
    for (d in directivesToCreate) {
      uncommittedChanges.add(Edit.Create(d))
      createInternal(d)
    }

    for (simResults in upToDateSimResultsSet) {
      simResults.get()?.setStale(true)
    }

    upToDateSimResultsSet = mutableSetOf()
  }

  private fun deleteCascadeRecursive(directive: Directive<AnyDirective>, allDirectives: Directives<AnyDirective>): List<Directive<AnyDirective>> {
    val recurse = allDirectives.collect().flatMap { d ->
      when (val s = d.start) {
        is DirectiveStart.Anchor -> {
          if (s.parentId == directive.id) deleteCascadeRecursive(d, allDirectives)
          else listOf()
        }
        else -> listOf()
      }
    }
    return recurse + listOf(directive)
  }

  override fun delete(id: ActivityDirectiveId, strategy: DeletedAnchorStrategy) {
    val matchingDirectives = plan.directives().filter { it.id == id }.collect()
    if (matchingDirectives.isEmpty()) throw Exception("attempted to delete activity by ID that does not exist: $id")
    if (matchingDirectives.size > 1) throw Exception("multiple activities with ID found: $id")

    delete(matchingDirectives.first(), strategy)
  }

  override fun commit() {
    // Early return if there are no changes. This prevents multiple commits from sharing ownership of the set,
    // because new sets are only created when edits are made.
    // Probably unnecessary, but shared ownership freaks me out enough already.
    if (uncommittedChanges.isEmpty()) return

    val newCommittedChanges = uncommittedChanges
    val newTotalDiff = committedChanges.diff.toMutableSet()

    for (newChange in newCommittedChanges) {
      val inverse = newChange.inverse()
      if (newTotalDiff.contains(inverse)) {
        newTotalDiff.remove(inverse)
      } else {
        newTotalDiff.add(newChange)
      }
    }

    uncommittedChanges = mutableListOf()

    // Create a commit that shares ownership of the simResults set.
    committedChanges = Commit(newTotalDiff, upToDateSimResultsSet)
  }

  override fun rollback(): List<Edit> {
    // Early return if there are no changes, to keep staleness accuracy
    if (uncommittedChanges.isEmpty()) return emptyList()

    val result = uncommittedChanges
    uncommittedChanges = mutableListOf()
    for (edit in result) {
      when (edit) {
        is Edit.Create -> deleteInternal(edit.directive.id)
        is Edit.Delete -> createInternal(edit.directive)
      }
    }
    for (simResult in upToDateSimResultsSet) {
      simResult.get()?.setStale(true)
    }
    for (simResult in committedChanges.upToDateSimResultsSet) {
      simResult.get()?.setStale(false)
    }
    upToDateSimResultsSet = committedChanges.upToDateSimResultsSet
    return result
  }

  override fun simulate(options: SimulateOptions): SimulationResults {
    simulateInternal(options)
    return latestResults()!!
  }

}
