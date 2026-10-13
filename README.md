# Donut Flipper (standalone Fabric mod, 1.21.11) - no Meteor needed

## Build
1. Generate a blank project at https://fabricmc.net/develop (Minecraft 1.21.11, Yarn, Java 21) - this gives you the Gradle wrapper (`gradlew`).
2. Copy `src/main/java/dev/donutflipper/` and `src/main/resources/*` from this project into it (replace its example mod class + fabric.mod.json), and use the versions from its gradle.properties.
3. `./gradlew build` -> `build/libs/donut-flipper-1.0.0.jar`
4. Put the jar in `.minecraft/mods/` next to Fabric API.

## Use (in game) - no commands
- **F8** opens the menu (dashboard): stats, learner, holdings, crafts, Bot ON/OFF, Dry-run, Settings.
- **F9** toggles the bot without opening the menu.
- Rebind both in Options > Controls > Key Binds > "Donut Flipper".
- The bot pauses while any menu is open, so close the dashboard to let it run.

Data is saved in `.minecraft/config/donut-flipper/` (brain.json, config.json, optional recipes.json).

## First run
Dry-run is ON by default. Turn the bot on and watch chat: if it says "no prices parsed", fix
`priceRegex` / `ahTitle` in Settings. When the logged candidates look right, turn dry-run OFF.

## Get the .jar without installing anything (GitHub Actions)
1. Create a free GitHub account and a new repository.
2. Upload the CONTENTS of this folder (including the hidden `.github` folder) to the repo.
3. Open the repo's **Actions** tab -> "Build jar" -> wait for the green check.
4. Download the `donut-flipper-jar` artifact, unzip it, and use `donut-flipper-1.0.0.jar` (NOT the -sources one).
5. Put it in `.minecraft/mods/` with Fabric API.
If the build goes red, open the failed step and send me the error text.
