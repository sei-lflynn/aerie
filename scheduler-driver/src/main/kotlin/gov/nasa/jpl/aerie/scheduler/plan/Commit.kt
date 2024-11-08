package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit
import java.lang.ref.WeakReference

data class Commit(
  val diff: List<Edit>,

  /**
   * A record of the simulation results objects that were up-to-date when the commit
   * was created.
   *
   * This has SHARED OWNERSHIP with [InMemoryEditablePlan]; the editable plan may add more to
   * this list AFTER the commit is created.
   */
  val simResultsUpToDate: MutableSet<WeakReference<MerlinToProcedureSimulationResultsAdapter>>
)
