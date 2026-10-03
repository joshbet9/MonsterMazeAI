import json
import tempfile
from pathlib import Path

from build_human_locomotion_dataset import write_samples


def write_jsonl(path: Path, rows):
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def test_importer_recovers_scoreboard_stage_and_clamps_action():
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        run_id = "human-speed-run-test"

        (root / f"{run_id}-manifest.json").write_text(
            json.dumps({
                "recordType": "manifest",
                "jsonVersion": 2,
                "files": [
                    f"{run_id}-input.jsonl",
                    f"{run_id}-movement.jsonl",
                    f"{run_id}-world.jsonl",
                    f"{run_id}-maze.jsonl",
                    f"{run_id}-monsters.jsonl",
                ],
            }) + "\n",
            encoding="utf-8",
        )

        write_jsonl(root / f"{run_id}-input.jsonl", [
            {"recordType": "header"},
            {
                "tick": 10, "forward": 1, "strafe": 0, "yawDelta": 45,
                "jump": True, "sprintKey": True,
                "rawUseItem": True, "mouseRightPulse": True,
            },
            {
                "tick": 11, "forward": 0, "strafe": -1, "yawDelta": -100,
                "jump": False, "sprintKey": False,
                "rawUseItem": False, "mouseRightPulse": False,
            },
        ])

        write_jsonl(root / f"{run_id}-movement.jsonl", [
            {"tick": 10, "x": 1.5, "y": 64, "z": 1.5, "vx": 0, "vy": 0, "vz": 0, "yaw": 0, "grounded": True},
            {"tick": 11, "x": 1.5, "y": 64, "z": 1.5, "vx": 0, "vy": 0, "vz": 0, "yaw": 0, "grounded": True},
        ])

        world_common = {
            "stage": 99,
            "mazeDetected": True,
            "alive": True,
            "mazePattern": 1,
            "kit": "JUMPER",
            "jumpCharges": 3,
            "abilityCharges": 0,
            "health": 20,
            "scoreboardLines": [
                "1", "Stage", "12", "35 Seconds",
                "Safe Pad", "1", "Players", "Modern", "Mode",
            ],
            "center": {"x": 0, "y": 64, "z": 0},
            "activePad": {"row": 50, "column": 50, "reached": False},
        }
        write_jsonl(root / f"{run_id}-world.jsonl", [
            {"tick": 10, "phaseTimerSeconds": 35, **world_common},
            {"tick": 11, "phaseTimerSeconds": 34, **world_common},
        ])

        maze = ["0" * 99 for _ in range(99)]
        maze[50] = "0" * 49 + "1" + "0" * 49
        write_jsonl(root / f"{run_id}-maze.jsonl", [
            {"tick": 10, "stage": 12, "maze": maze, "physicalFloor": maze}
        ])
        write_jsonl(root / f"{run_id}-monsters.jsonl", [
            {"tick": 10, "monsters": []},
            {"tick": 11, "monsters": []},
        ])

        output = root / "locomotion.jsonl"
        report = write_samples(root, output)
        assert report["samples"] == 2

        rows = [json.loads(line) for line in output.read_text().splitlines()]
        assert rows[0]["stage"] == 12
        assert rows[0]["action"]["yawDelta"] == 30.0
        assert rows[0]["action"]["useAbility"] is True
        assert len(rows[0]["observation"]) == 96
        assert rows[0]["observation"][27] == 1.0


if __name__ == "__main__":
    test_importer_recovers_scoreboard_stage_and_clamps_action()
    print("human locomotion importer smoke test: PASS")
