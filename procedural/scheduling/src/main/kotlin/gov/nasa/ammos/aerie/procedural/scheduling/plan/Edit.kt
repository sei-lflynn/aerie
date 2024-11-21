package gov.nasa.ammos.aerie.procedural.scheduling.plan

import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive
import gov.nasa.jpl.aerie.types.ActivityDirectiveId

/**
 * Edits that can be made to the plan.
 *
 * Currently only creating new activities is supported.
 */
sealed interface Edit {
  fun inverse(): Edit

  /** Create a new activity from a given directive. */
  data class Create(/***/ val directive: Directive<AnyDirective>): Edit {
    override fun inverse() = Delete(directive)
  }

  /** Delete an activity, specified by directive id. */
  data class Delete(/***/ val directive: Directive<AnyDirective>): Edit {
    override fun inverse() = Create(directive)
  }
}
