package gov.nasa.ammos.aerie.procedural.scheduling

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema

interface SchedulingProcedureMapper<T: Goal> {
  fun valueSchema(): ValueSchema
  fun serialize(procedure: T): SerializedValue
  fun deserialize(arguments: SerializedValue): T
}
