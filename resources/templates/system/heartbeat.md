# Heartbeat Jobs

Startup jobs that run (or catch up) every time the assistant launches.
Add a checklist item per recurring job. The scheduler reads this file and
registers each enabled job on init.

## Jobs

- [ ] periodic-reflection: interval=86400000, description="Summarize recent cognition/ content into reflections"
- [ ] memory-consolidation: interval=604800000, description="Merge daily memory files older than 7 days into a summary"
