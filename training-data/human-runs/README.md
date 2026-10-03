# Human observer runs

These files are raw Minecraft 1.8.9 observer output captured from real human
Speed runs. They are calibration / imitation data, not authoritative game
state for deployment.

Current corpus:

| Run | Kit | Mode | Reached stage |
| --- | --- | --- | ---: |
| 20260930-174746-835 | MAVERICK | SPEED | 49 |
| 20260930-181006-247 | REPULSOR | SPEED | 26 |
| 20260930-195111-185 | JUMPER | SPEED | 38 |
| 20260930-195904-165 | SLOWBALLER | SPEED | 1 |
| 20260930-195916-359 | SLOWBALLER | SPEED | 21 |
| 20260930-200313-358 | BODY_BUILDER | SPEED | 41 |
| 20261003-143847-349 | JUMPER | SPEED | 8 |
| 20261003-143935-984 | JUMPER | SPEED | 44 |
| 20261003-144959-024 | SLOWBALLER | SPEED | 25 |
| 20261003-145504-318 | BODY_BUILDER | SPEED | 25 |
| 20261003-150101-060 | REPULSOR | SPEED | 14 |
| 20261003-150412-459 | MAVERICK | SPEED | 37 |

The run IDs are preserved exactly so derived samples can always be traced back
to the original telemetry.

Each run contains synchronized streams for movement, input, world state,
objectives, navigation, monsters, maze, inventory, collision and events.

Use `training/human_dataset.py` to build the canonical v2 96-feature rows.
The converter also produces a catalog, preserves six-dimensional actions, and
keeps the raw telemetry separate from the derived training set.

Important training rule: do not treat the current human corpus as a complete
CPU policy by itself. It is strong demonstration data for locomotion/tactical
calibration. Competitor behaviour will be supplied later by source-faithful
Engine self-play and heterogeneous CPU profiles.
