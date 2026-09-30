import json
import tempfile
import unittest
from pathlib import Path

from human_run_analyzer import classify_death, compare_with_simulator, normalize_run, scoreboard_info


class HumanRunAnalyzerTest(unittest.TestCase):
    def test_scoreboard_parser_handles_value_before_label(self):
        info = scoreboard_info([
            "3 (Maverick)",
            "13", "Stage",
            "28 Seconds", "Safe Pad",
            "Speed", "Mode",
        ])
        self.assertEqual(info["kit"], "MAVERICK")
        self.assertEqual(info["stage"], 13)
        self.assertEqual(info["safePadSeconds"], 28)
        self.assertEqual(info["mode"], "speed")

    def test_declared_kit_survives_observer_and_scoreboard_conflicts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "human-speed-run-test-manifest.json").write_text(
                json.dumps({
                    "recordType": "manifest",
                    "jsonVersion": 2,
                    "minecraftVersion": "1.8.9",
                    "declaredKit": "MAVERICK",
                    "files": [
                        "human-speed-run-test-world.jsonl",
                        "human-speed-run-test-movement.jsonl",
                        "human-speed-run-test-input.jsonl",
                        "human-speed-run-test-navigation.jsonl",
                        "human-speed-run-test-monsters.jsonl",
                        "human-speed-run-test-events.jsonl",
                    ],
                    "boundary": "Minecraft18RunBoundary + Minecraft18Observer",
                }) + "\n" +
                json.dumps({
                    "recordType": "footer",
                    "records": 3,
                    "reason": "CHAT:Solo run over — reached stage 2!",
                }) + "\n",
                encoding="utf-8",
            )

            def write_stream(name, rows):
                (root / f"human-speed-run-test-{name}.jsonl").write_text(
                    "\n".join(
                        json.dumps(x)
                        for x in [{"recordType": "header", "jsonVersion": 2, "stream": name}, *rows]
                    ) + "\n",
                    encoding="utf-8",
                )

            write_stream("movement", [
                {"tick":100,"stage":1,"x":0,"y":0,"z":0,"vx":0,"vy":0,"vz":0,"yaw":0,"pitch":0,"yawDelta":0,"grounded":True,"airborne":False,"displacement":0,"horizontalSpeed":0,"healthDelta":0},
                {"tick":101,"stage":1,"x":1,"y":0,"z":0,"vx":0.3,"vy":0,"vz":0,"yaw":0,"pitch":0,"yawDelta":0,"grounded":True,"airborne":False,"displacement":1,"horizontalSpeed":0.3,"healthDelta":0},
                {"tick":102,"stage":1,"x":2,"y":0,"z":0,"vx":0.3,"vy":0,"vz":0,"yaw":0,"pitch":0,"yawDelta":0,"grounded":True,"airborne":False,"displacement":1,"horizontalSpeed":0.3,"healthDelta":0},
            ])
            write_stream("input", [
                {"tick":100,"forward":1,"strafe":0,"jump":False,"sprintKey":True},
                {"tick":101,"forward":1,"strafe":0,"jump":True,"sprintKey":True,"rawJump":True},
                {"tick":102,"forward":1,"strafe":0,"jump":False,"sprintKey":True},
            ])
            write_stream("world", [
                {
                    "tick":100,"stage":1,"phaseTimerSeconds":60,"alive":True,"completed":False,"mazeDetected":True,"mazePattern":2,
                    "kit":"JUMPER","jumpCharges":3,"abilityCharges":1,"health":20,"healthDelta":0,
                    "scoreboardTitle":"Monster Maze","scoreboardLines":["3 (Repulsor)","Stage","1","60 Seconds","Safe Pad","Speed","Mode"],
                    "activePad":{"row":11,"column":20,"distanceSq":4,"reached":False},
                },
                {
                    "tick":101,"stage":1,"phaseTimerSeconds":60,"alive":True,"completed":False,"mazeDetected":True,"mazePattern":2,
                    "kit":"JUMPER","jumpCharges":3,"abilityCharges":1,"health":20,"healthDelta":0,
                    "scoreboardTitle":"Monster Maze","scoreboardLines":["3 (Maverick)","Stage","1","60 Seconds","Safe Pad","Speed","Mode"],
                    "activePad":{"row":11,"column":20,"distanceSq":4,"reached":False},
                },
                {
                    "tick":102,"stage":2,"phaseTimerSeconds":60,"alive":True,"completed":False,"mazeDetected":True,"mazePattern":2,
                    "kit":"JUMPER","jumpCharges":3,"abilityCharges":1,"health":20,"healthDelta":0,
                    "scoreboardTitle":"Monster Maze","scoreboardLines":["3 (Repulsor)","Stage","1","60 Seconds","Safe Pad","Speed","Mode"],
                    "activePad":{"row":12,"column":20,"distanceSq":4,"reached":False},
                },
            ])
            write_stream("navigation", [
                {"tick":100,"activePad":{"row":11,"column":20,"reached":False},"targetDistance":10,"targetBearing":0,"movementBearing":0},
                {"tick":101,"activePad":{"row":11,"column":20,"reached":False},"targetDistance":9,"targetBearing":0,"movementBearing":0},
                {"tick":102,"activePad":{"row":12,"column":20,"reached":False},"targetDistance":8,"targetBearing":0,"movementBearing":0},
            ])
            write_stream("monsters", [
                {"tick":t,"monsters":[],"count":0,"observerCount":0,"radius":20} for t in (100,101,102)
            ])
            write_stream("events", [{"tick":100,"recordIndex":0,"event":"GAME_START","detail":""}])

            result = normalize_run(root, root)
            summary = result["summary"]
            self.assertEqual(summary["conditions"]["kit"], "MAVERICK")
            self.assertEqual(summary["conditions"]["mode"], "speed")
            self.assertEqual(summary["conditions"]["pattern"], 2)
            self.assertEqual(summary["run"]["stageReached"], 2)
            self.assertTrue(any(a["type"] == "KIT_CONFLICT" for a in result["anomalies"]))
            self.assertTrue(any(a["type"] == "WORLD_STAGE_CONFLICT" for a in result["anomalies"]))
            self.assertTrue((root / "analysis" / "summary.json").exists())
            self.assertTrue((root / "normalized" / "decisions.jsonl").exists())

    def test_simulator_condition_mismatch_is_explicit(self):
        human = {
            "conditions": {"mode": "speed", "kit": "REPULSOR", "pattern": 3},
            "run": {"stageReached": 10, "durationSeconds": 20.0},
            "metrics": {},
        }
        simulator = {
            "conditions": {"mode": "speed", "kit": "JUMPER", "pattern": 3},
            "simulator": {"stageReached": 9, "durationSeconds": 18.0},
        }
        comparison = compare_with_simulator(human, simulator)
        self.assertFalse(comparison["conditionMatch"])
        self.assertEqual(comparison["conditionDifferences"][0]["field"], "kit")

    def test_death_classification_uses_knockback_candidate(self):
        events = [{"type": "KNOCKBACK_CANDIDATE", "tick": 100}]
        self.assertEqual(classify_death(events, "CHAT:Fell off the maze!"), "MOB_KNOCKBACK")


if __name__ == "__main__":
    unittest.main()
