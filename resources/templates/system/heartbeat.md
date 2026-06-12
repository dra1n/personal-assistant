# Heartbeat Jobs

Startup jobs that run (or catch up) every time the assistant launches.
Add a checklist item per recurring job. The scheduler reads this file and
registers each enabled job on init. Job types must use qualified keyword format:
`namespace/name` (e.g. `scheduler/periodic-reflection`). Unqualified names are skipped with a warning.

## Jobs

- [ ] scheduler/periodic-reflection: interval=86400000, description="Summarize recent cognition/ content into reflections"
- [ ] scheduler/consolidate-wisdom: interval=604800000, description="Deduplicate and merge permanent facts in memory/memory.md"
