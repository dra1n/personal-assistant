# Tools & Infrastructure

This file is the assistant's infrastructure cheat sheet **and** the source of
truth for what the filesystem tools may touch. Edit the allowlist below to widen
or restrict where the assistant can read and write.

## Path allowlist

Each line inside the fenced `allowlist` block is `<path>  <capabilities>`, where
capabilities are any of `read`, `write`, `deny` (space-separated).

Rules:
- The **longest-prefix** matching root wins.
- `deny` always wins — a path under any `deny` root is rejected outright.
- A path matching **no** root is rejected (default-deny).
- `write` does **not** imply `read`; list both to grant both.
- Paths may be absolute, start with `~` (home), or be relative to the
  assistant's data root (`.` is the data root itself).
- Lines starting with `#` are comments.

```allowlist
# Out of the box the assistant gets a sandbox under its data directory.
# Its own identity, event log, and database are NOT writable here.
# Add lines like `~/Projects  read write` or `~/.ssh  deny` to taste.
workspace  read write
```

## Infrastructure notes

<!-- Cheat sheet for your setup: device info, ffmpeg paths, cameras, how to
     synthesize voice, etc. Fill in as needed — the assistant reads this file. -->
