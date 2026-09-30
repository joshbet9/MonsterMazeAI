package me.monstermazeai.game;

import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Source-equivalent SafePad candidate selection from MonsterMaze's
 * MazeGenerator/GameManager implementation.
 *
 * It intentionally operates on the raw 99x99 map values rather than inferred
 * walkability, because the server's valid pad list is built during generation.
 */
public final class SourcePadSpawner {
    private final Random random;
    private final List<Cell> validSafePadSpawns;

    public SourcePadSpawner(MazeModel maze, Random random) {
        if (maze == null) throw new IllegalArgumentException("maze");
        if (random == null) throw new IllegalArgumentException("random");
        this.random = random;
        this.validSafePadSpawns = buildValidSafePadSpawns(maze);
    }

    public List<Cell> validSafePadSpawns() {
        return List.copyOf(validSafePadSpawns);
    }

    /** Initial pad: furthest valid candidate from maze center, random tie break. */
    public Cell initialPad() {
        return findFurthest(new Cell(49, 49), validSafePadSpawns);
    }

    /** Later pads: >=40 blocks from every currently active/old pad, random candidate. */
    public Cell nextPad(List<Cell> avoid) {
        if (avoid == null || avoid.isEmpty()) return initialPad();

        ArrayList<Cell> best = new ArrayList<>();
        for (Cell candidate : validSafePadSpawns) {
            boolean allowed = true;
            for (Cell existing : avoid) {
                if (existing == null) continue;
                if (distanceSq(candidate, existing) < 40.0 * 40.0) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) best.add(candidate);
        }
        if (best.isEmpty()) return findFurthest(new Cell(49, 49), validSafePadSpawns);
        return best.get(random.nextInt(best.size()));
    }

    private List<Cell> buildValidSafePadSpawns(MazeModel maze) {
        List<Cell> paths = new ArrayList<>();
        List<Cell> spawns = new ArrayList<>();
        List<Cell> glass = new ArrayList<>();
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                int value = maze.raw(r, c);
                if (value == 1 || value == 2 || value == 5 || value == 6) paths.add(new Cell(r, c));
                if (value == 2) spawns.add(new Cell(r, c));
                if (value == 4 || value == 6) glass.add(new Cell(r, c));
            }
        }

        List<Cell> filtered = new ArrayList<>();
        for (Cell p : paths) {
            boolean ok = true;
            for (Cell s : spawns) {
                if (distanceSq(p, s) < 100.0) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            for (Cell g : glass) {
                if (distanceSq(p, g) < 49.0) {
                    ok = false;
                    break;
                }
            }
            if (ok) filtered.add(p);
        }

        List<Cell> candidates = new ArrayList<>(filtered);
        List<Cell> safeZones = new ArrayList<>();
        Cell center = new Cell(49, 49);
        for (int i = 0; i < 8 && !candidates.isEmpty(); i++) {
            ArrayList<Cell> avoid = new ArrayList<>(safeZones);
            avoid.add(center);
            Cell chosen = findLocationAwayFrom(candidates, avoid);
            safeZones.add(chosen);
            candidates.removeIf(c -> distanceSq(chosen, c) <= 36.0);
        }

        ArrayList<Cell> valid = new ArrayList<>();
        for (Cell p : filtered) {
            boolean ok = true;
            for (Cell zone : safeZones) {
                if (distanceSq(p, zone) < 49.0) {
                    ok = false;
                    break;
                }
            }
            if (ok) valid.add(p);
        }
        return List.copyOf(valid);
    }

    // The source uses Location.distanceSquared, equivalent here because all
    // path candidates are integer block coordinates with the same Y.
    private static double distanceSq(Cell a, Cell b) {
        double dr = a.row() - b.row();
        double dc = a.column() - b.column();
        return dr * dr + dc * dc;
    }

    private Cell findLocationAwayFrom(List<Cell> locations, List<Cell> awayFrom) {
        Cell best = null;
        double bestDistance = -1.0;
        for (Cell location : locations) {
            double closest = Double.POSITIVE_INFINITY;
            for (Cell away : awayFrom) {
                closest = Math.min(closest, distanceSq(location, away));
            }
            if (closest > bestDistance) {
                bestDistance = closest;
                best = location;
            }
        }
        if (best == null) throw new IllegalStateException("No SafePad safe-zone candidate");
        return best;
    }

    private Cell findFurthest(Cell from, List<Cell> list) {
        if (list.isEmpty()) throw new IllegalStateException("No valid SafePad spawn candidates");
        double bestSq = -1.0;
        ArrayList<Cell> best = new ArrayList<>();
        for (Cell cell : list) {
            double d = distanceSq(from, cell);
            if (d > bestSq) {
                bestSq = d;
                best.clear();
                best.add(cell);
            } else if (Double.compare(d, bestSq) == 0) {
                best.add(cell);
            }
        }
        return best.get(random.nextInt(best.size()));
    }

}
