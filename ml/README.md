# Monster Maze ML Route Learning

The ML layer is deliberately downstream of the source-faithful simulator.

## Data flow

The Java MonsterAwareRoutePlanner already evaluates multiple physical route
candidates with TacticalRouteSimulator. When ML recording is enabled, every
candidate state/result pair is appended to JSONL:

~~~text
GameState + route candidate
        |
        v
TacticalRouteSimulator  <-- authoritative label
        |
        v
ml-data/route-training.jsonl
        |
        v
ml/route_ranker.py
        |
        v
route-value-model.json
~~~

The first model predicts a scalar route cost. Lower is better. A failed route
receives a large cost, while successful routes are primarily ordered by arrival
time and then penalised for damage, gaps, and unnecessary route length.

The initial model is shadow-only. It does not replace the deterministic planner
or movement motor yet. This prevents a poorly trained model from changing
Minecraft behaviour before simulator validation shows that it is learning
something useful.

## Local collection

From the repository root:

~~~powershell
Remove-Item .\ml-data\route-training.jsonl -ErrorAction SilentlyContinue
mvn -B -ntp -pl common -am `
  "-Dtest=SpeedFullRunDiagnosticTest,ModernFullRunDiagnosticTest" `
  "-Dmonstermaze.ml.record=true" `
  "-Dmonstermaze.ml.output=ml-data/route-training.jsonl" `
  "-Dsurefire.useFile=false" test

python -m pip install numpy
python .\ml\route_ranker.py inspect --input .\ml-data\route-training.jsonl
python .\ml\route_ranker.py train `
  --input .\ml-data\route-training.jsonl `
  --output .\ml-data\route-value-model.json
~~~

The dataset groups all candidates produced from one source state together.
Training/validation splitting is therefore group-based, so candidate leakage
does not make the validation score look better than it really is.

## Design rules

The simulator remains authoritative for:

- player movement and collision
- monster movement and bump physics
- kit/ability behaviour
- jump timing
- gap execution
- pad timing/topology

The learned model is intended to learn which route is promising under the
current moving-mob state, especially the "keep momentum while changing route"
problem. It is not allowed to invent a new physics rule.

The eventual live integration is:

1. generate a small set of physically executable candidates
2. score candidates with the learned model
3. keep the source-faithful support/collision guard
4. reserve the full tactical simulator for uncertain/high-risk states
5. keep a deterministic fallback whenever the model is unavailable or uncertain

This creates a safe path from expensive search to learned decision-making rather
than replacing the mechanics with a black box.


## Human behavior model

The existing human-run recorder produces synchronized input, movement,
navigation, world and monster streams. The optional CI job pulls the six
recorded calibration runs from the `feature/human-run-analysis` branch,
converts them into fixed-size state/action examples, and trains a separate
behavior-cloning model.

The behavior model learns:

- forward and strafe input
- jump and sprint decisions
- yaw correction

while observing movement state, pad direction, velocity, health, kit/mode,
and nearby monster pressure. It is intentionally separate from the route-value
model: route choice answers "which physical path is promising?", while the
behavior model answers "how did a strong human control the player in this
situation?".

The two models can later be combined behind the deterministic source-faithful
motor and simulator safety checks.
