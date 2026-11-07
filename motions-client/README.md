# Motions Client (Fabric, Minecraft 1.21.11)

Client-side mod: animated dark UI, full A-Z item picker, auction setup + live auction card, saved settings.

## Build on Replit
1. Create a new Repl (any template, e.g. "Bash"/"Java"), upload ALL files from this zip (keep folders), or import the zip.
2. Make sure `replit.nix` and `.replit` are in the project root (Replit may ask to reload the shell).
3. Open Shell and run:  `bash build.sh`   (first build downloads Minecraft + mappings; takes several minutes)
4. Download `build/libs/motions-client-1.0.0.jar` (right-click it in the file tree -> Download).
   Do NOT use the `-sources` jar.

Memory: remapping Minecraft needs roughly 2 GB RAM. If Replit kills the build, use a paid Repl with more RAM.
If a version fails to resolve, check https://fabricmc.net/develop and update gradle.properties.

## Install
1. Install Minecraft 1.21.11 + Fabric Loader 0.18.4+ (https://fabricmc.net/use/installer/).
2. Put Fabric API 0.141.1+1.21.11 (or newer for 1.21.11) from Modrinth in `.minecraft/mods`.
3. Put `motions-client-1.0.0.jar` in the same `mods` folder, launch the Fabric profile.

## Use
- Press **Right Shift** in-game (rebind: Options > Controls > Key Binds > Motions Client).
- Auction tab: pick any item (no inventory needed) -> price -> duration -> Start auction.
- Fully client-side: nothing is sent to the server. The auction is a local card with a countdown.
  It pops onto your screen, the item floats and sparkles, and payments are read from chat while an auction is on (e.g. "Steve paid you $500M", "You have been paid $500M by Steve").
- Layout tab: drag the card around a mini screen, scale it 0.5x-1.5x, snap to corners, or reset.
- When the auction ends the winner is shown for 8 seconds; every payment shows a small green amount that fades out.
- Sounds can be turned off in Settings > UI sounds.
- Config file: `.minecraft/config/motions-client.json`.

## Build without Replit (recommended if Replit runs out of memory)
### Option A: GitHub Actions (free, no install)
1. Create a free GitHub account and a new repository.
2. Upload ALL project files (including the hidden `.github` folder) and commit.
3. Open the repo's **Actions** tab -> "Build Motions Client" -> wait ~5-10 min.
4. Open the finished run, download the **motions-client-jar** artifact, unzip it to get the .jar.

### Option B: Your own PC (needs 4 GB+ free RAM)
1. Install JDK 21 (e.g. Temurin) and Gradle 9.2.1 (or run `bash build.sh` on Linux/macOS/WSL).
2. In the project folder run: `gradle build`
3. Jar is at `build/libs/motions-client-1.0.0.jar`.
