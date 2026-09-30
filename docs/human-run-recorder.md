# 1.8.9 human run recorder

The Minecraft 1.8.9 client contains an optional, AI-independent recorder for collecting strong human Monster Maze runs. It uses the same `Minecraft18Observer` state as the AI and the shared `Minecraft18RunBoundary` rules for run start/end.

## Install and build

From PowerShell in `C:\MonsterMazeAI`:

### Build the Java-17 common runtime

```powershell
Set-Location C:\MonsterMazeAI
$jdk17 = Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter "jdk-17*" | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk17) { throw "Java 17 JDK not found under $env:ProgramFiles\Eclipse Adoptium" }
$env:JAVA_HOME = $jdk17.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -B -ntp -pl common -am package -DskipTests
```

This creates the runtime sidecar at:

`C:\MonsterMazeAI\common\target\common-0.1.0-SNAPSHOT-runtime.jar`

### Build the Minecraft 1.8.9 Forge mod

```powershell
Set-Location C:\MonsterMazeAI
$jdk8 = Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter "jdk-8*" | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk8) {
    $jdk8 = Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium" -Directory -Filter "jdk-8*" | Sort-Object Name -Descending | Select-Object -First 1
}
if (-not $jdk8) { throw "Java 8 JDK not found under the normal Eclipse Adoptium locations" }
$env:JAVA_HOME = $jdk8.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
Set-Location C:\MonsterMazeAI\minecraft-1.8
gradle --no-daemon build
```

The adapter build is configured for Minecraft `1.8.9-11.15.1.2318-1.8.9`.

The resulting mod JAR is under:

`C:\MonsterMazeAI\minecraft-1.8\build\libs\`

Copy the generated `monster-maze-ai-1.8-*.jar` into the `.minecraft\mods\` directory for the same Forge 1.8.9 installation. The JAR already contains the Java-8 common API and the Java-17 runtime sidecar.

## Using the recorder

Start Minecraft normally and join Monster Maze.

The recorder is **F7**. It is independent of the AI **F8** toggle.

Press F7 before starting a run. You do not need to toggle it for every game: leave it enabled and it will automatically create a new run directory/file set for each detected game.

The recorder starts a game when `Minecraft18Observer` reports `inMonsterMaze=true`.

It ends a game after the final tick has been captured when any of the shared terminal conditions occurs:

- `state.completed=true`
- `state.alive=false`
- `state.inMonsterMaze=false`
- a terminal Monster Maze chat message matching the existing AI rule: `fell off the maze`, `solo run over`, or `you weren't on the safe pad`
- the Minecraft world is left

A terminal chat event is marked first and the next client END tick is still written before the files are closed, so the final observed physics state is retained.

## Output

Files are created under:

`%APPDATA%\..\Roaming\.minecraft\human-runs\`

More precisely, the recorder uses Minecraft's `mcDataDir`, so the exact directory follows the active client's `.minecraft` data directory.

Each game gets these focused files:

```
human-speed-run-<timestamp>-manifest.json
human-speed-run-<timestamp>-movement.jsonl
human-speed-run-<timestamp>-input.jsonl
human-speed-run-<timestamp>-world.jsonl
human-speed-run-<timestamp>-navigation.jsonl
human-speed-run-<timestamp>-monsters.jsonl
human-speed-run-<timestamp>-maze.jsonl
human-speed-run-<timestamp>-inventory.jsonl
human-speed-run-<timestamp>-collision.jsonl
human-speed-run-<timestamp>-events.jsonl
```

### movement.jsonl

Per-tick kinematics and Minecraft movement facts:

- position and velocity
- yaw, pitch and per-tick yaw delta
- horizontal displacement and speed
- grounded/airborne state
- fall distance
- horizontal/vertical/general collision flags
- sprinting and sneaking state
- step height and jump movement factor
- vanilla walk-distance counter
- health delta

### input.jsonl

Per-tick actual controls:

- normalized Minecraft movement input
- raw W/A/S/D states
- raw jump/sprint/sneak states
- attack and use-item key states
- left/right mouse press pulses
- yaw delta and whether it stayed within the common 30-degree AI action limit

This is the input stream we can replay against the common `Action` representation.

### world.jsonl

Per-tick game state needed for stage/timer/ability decisions:

- world tick and tick delta
- stage
- SafePad timer and elapsed run time
- Monster Maze/alive/completed/detection state
- maze pattern
- detected kit
- jump and ability charges
- health and selected hotbar slot
- scoreboard title and lines
- maze centre
- active pad

### navigation.jsonl

Per-tick navigation measurements derived only from observed geometry/state:

- logical maze cell
- active pad
- player-to-pad vector and distance
- target bearing
- actual movement bearing
- velocity bearing
- displacement and horizontal speed

This makes route efficiency measurable without asserting that a particular route was optimal.

### monsters.jsonl

Per-tick Monster Maze monsters within 32 blocks of the player, including:

- stable entity ID
- gameplay and visual type
- position
- velocity

The line also contains the total monsters observed by the authoritative observer. Local monster telemetry is used because that is the range most directly relevant to movement decisions while keeping the file manageable.

### maze.jsonl

The complete 99x99 logical maze and 99x99 physical-floor state, but only when the corresponding matrix changes. Rows are compact strings of cell values rather than a huge nested JSON array.

This gives exact route geometry and records centre/pad physical changes without duplicating tens of thousands of cells on every tick.

### inventory.jsonl

Full non-empty inventory snapshots only when the inventory actually changes:

- hotbar slot
- item ID and metadata
- stack count
- display name

This is important for reconstructing kit selection, charges, item consumption and ability state without copying the entire inventory every tick.

### collision.jsonl

When the local block environment changes, records a 5x5x4 block volume around the player:

- relative block coordinates
- Minecraft block ID
- metadata

Combined with the movement stream, this lets us investigate exact collision/floor situations around jumps, edges and falls.

### events.jsonl

Sparse causal transitions:

- GAME_START / GAME_END
- stage changes
- active pad changes
- pad reached
- ground/air transitions
- health loss
- charge changes
- hotbar changes
- mouse/jump/sprint input events
- local monster set changes
- terminal chat/end reason

The stream is intentionally event-oriented rather than another copy of every tick.

## Recommended recording procedure

For calibration, use the recorder with the AI disabled (F8 off) and play a normal strong Speed-mode run.

A good run is more useful than a scripted/TAS-perfect run because we want to see realistic:

- route commitment
- correction
- turning
- jumping
- speed preservation
- monster interactions
- ability timing
- mistakes and recoveries

Once the run ends, upload the **entire set of files for that timestamp**. The manifest identifies the component files and lets us correlate every stream by `tick` and `recordIndex`.

The resulting dataset is intended to support:

`real human inputs + real world state -> simulator replay -> human/AI comparison -> identify repeated efficiency gaps -> improve AI`
