# Monster Maze ML training

## Human policy v2

training/human_dataset.py produces the canonical human dataset:

- schema version: 2
- observation width: 96
- action: forward, strafe, jump, sprint, yawDelta, useAbility
- source: recorded human runs

Use ml/human_policy_v2.py for the human-control model. It keeps whole human
runs together when building the validation holdout, so consecutive ticks from
one run cannot leak into validation.

Inspect a dataset:

\`\`\`powershell
& .\.venv\Scripts\python.exe .\ml\human_policy_v2.py inspect \`
    --input .\training-data\derived\human-policy-v2.jsonl
\`\`\`

Train the human behaviour model:

\`\`\`powershell
& .\.venv\Scripts\python.exe .\ml\human_policy_v2.py train \`
    --input .\training-data\derived\human-policy-v2.jsonl \`
    --output .\ml-data\local\human-policy-v2-model.json
\`\`\`

The model is an auxiliary behaviour-cloning model. It does not replace the
source-faithful counterfactual policy model.

## Counterfactual policy model

The long-horizon policy trainer uses a different 52-feature state/action
contract and simulator-generated return labels. Human 96-feature rows are
therefore not silently reshaped into the counterfactual dataset.

The intended learning stack is:

1. human v2 data -> human-control behaviour model
2. source-faithful counterfactual rollouts -> long-horizon policy model
3. future policy integration may use both signals behind deterministic
   mechanics/safety checks

This separation prevents the human dataset's missing competitor/topology
information from being turned into invented simulator labels while preserving
the full 96-feature observation contract for future player modelling.
