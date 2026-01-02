# HomingBow (Paper 1.21.7)

This repo builds a small Paper plugin that makes arrows "home" toward nearby targets when shot from a special bow.

## How to get the JAR (no compiling on your PC)

1) Create a new GitHub repo (public or private)
2) Upload everything from this folder to the repo root
3) Go to **Actions** → **Build HomingBow** → **Run workflow**
4) Download the artifact: **HomingBow-jar** → contains `HomingBow-1.0.0.jar`

## Install

- Put `HomingBow-1.0.0.jar` into `/plugins/`
- Start server once to generate config
- Edit `plugins/HomingBow/config.yml`
  - Set `custom_model_data` to your ItemsAdder bow CMD
- Reload: `/homingbowreload`
