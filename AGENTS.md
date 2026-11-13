# STOP: this is the public repo

If this session was started in `phonemic`, tell the user right away that work must happen in `~/Projects/phone-mic` (private source of truth), then switch there (`cd ~/Projects/phone-mic`, read its `AGENTS.md`) and do the work there.

This repo must never receive test commits, experiments, WIP or fixups. It gets one clean commit per release, made from phone-mic via `sync-source.sh` after everything is working and the user is happy. Do not edit, commit or push here otherwise. The release procedure is in `phone-mic/AGENTS.md`.
