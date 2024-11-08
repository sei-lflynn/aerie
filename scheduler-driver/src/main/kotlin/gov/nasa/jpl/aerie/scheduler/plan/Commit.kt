package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit
import java.lang.ref.WeakReference

data class Commit(
  val diff: List<Edit>,
  val simResultsUpToDate: Set<WeakReference<MerlinToProcedureSimulationResultsAdapter>>
  val simResultsUpToDate: MutableSet<WeakReference<MerlinToProcedureSimulationResultsAdapter>>
)
