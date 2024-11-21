package gov.nasa.ammos.aerie.procedural.scheduling.plan

enum class DeletedAnchorStrategy {
  Error,
  Cascade,
  AnchorToParent,
  AnchorToPlan
}
