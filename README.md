# HomingBow (Paper 1.21.7) - mob only (fixed)

## Build jar (no local compiling)
GitHub → Actions → Build HomingBow → Run workflow → download artifact `HomingBow-jar`

## Install
- Put `HomingBow-1.0.1.jar` into `/plugins/`
- Start server once (creates config)
- Edit `plugins/HomingBow/config.yml`:
  - set `bow_match.custom_model_data` to your ItemsAdder bow CMD
- Reload config: `/homingbowreload` (or restart)

## Notes
- Never targets players (hard-locked)
- Extra safety: avoids choosing mob targets if the arrow-to-mob line passes too close to any player (config: avoid_players)
