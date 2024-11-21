package gov.nasa.ammos.aerie.procedural.scheduling.utils

import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults

interface PerishableSimulationResults: SimulationResults {
  fun setStale(stale: Boolean)
}
