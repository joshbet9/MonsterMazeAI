package me.monstermazeai.viewer;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.ability.AbilityState;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.SourcePadSpawner;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.SourceMazeLayouts;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.player.PlayerState;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.runtime.AutonomousMonsterMazeAgent;
import me.monstermazeai.sim.Simulator;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class SimulatorViewerMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(SimulatorViewer::new);
    }
}

final class SimulatorViewer {
    private final Simulation simulation = new Simulation();
    private final ViewerPanel panel = new ViewerPanel(simulation);
    private final JFrame frame = new JFrame("Monster Maze — 3D Mechanics Viewer");
    private final Timer timer;

    SimulatorViewer() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);

        timer = new Timer(50, this::tick);
        timer.setCoalesce(true);
        timer.start();

        panel.requestFocusInWindow();
    }

    private void tick(ActionEvent ignored) {
        if (!simulation.paused) {
            int ticks = simulation.ticksPerFrame;
            for (int i = 0; i < ticks && simulation.state.alive; i++) {
                simulation.tick(panel.inputAction());
            }
        }
        panel.repaint();
    }
}

final class Simulation {
    final GameState state = new GameState();
    int pattern = 0;
    Mode mode = Mode.MODERN;
    Kit kit = Kit.JUMPER;
    boolean paused;
    boolean aiEnabled;
    int ticksPerFrame = 1;

    private MazeModel maze;
    private Random monsterRandom;
    private Random padRandom;
    private MonsterSimulator monsterSimulator;
    private Simulator simulator;
    private SourcePadSpawner pads;
    private AutonomousMonsterMazeAgent agent;
    private int nextMonsterId;
    private int lastStage;

    Simulation() {
        reset();
    }

    void reset() {
        long seed = 0x4D4D4153494D0000L
                ^ ((long) pattern * 0x9E3779B97F4A7C15L)
                ^ ((long) kit.ordinal() * 0xBF58476D1CE4E5B9L)
                ^ ((long) mode.ordinal() * 0x94D049BB133111EBL);

        maze = new MazeModel(SourceMazeLayouts.maze(pattern));
        monsterRandom = new Random(seed ^ 0x6A09E667F3BCC909L);
        padRandom = new Random(seed ^ 0xBB67AE8584CAA73BL);

        state.tick = 0;
        state.player = new PlayerState();
        state.ability = new AbilityState();
        state.mode = mode;
        state.stage = 1;
        state.mazePattern = pattern;
        state.maze = maze;
        state.kit = kit;
        state.inMonsterMaze = true;
        state.alive = true;
        state.completed = false;
        state.player.x = 49.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 49.5;
        state.player.yaw = 0.0F;
        state.player.grounded = true;
        state.player.health = 20.0;
        state.player.maxHealth = 20.0;
        state.monsters.clear();
        state.oldPads.clear();
        state.oldPadDecaySeconds.clear();
        state.previewPadRow = -1;
        state.previewPadColumn = -1;

        AbilityModel abilities = new AbilityModel();
        monsterSimulator = new MonsterSimulator(
                maze, monsterRandom, 1.4, seed ^ 0x6A09E667F3BCC909L);
        simulator = new Simulator(
                new LegacyMazePhysics(), monsterSimulator, new CollisionModel(), abilities);
        simulator.initialise(state);

        pads = new SourcePadSpawner(maze, padRandom);
        Cell initial = pads.initialPad();
        state.activePadRow = initial.row();
        state.activePadColumn = initial.column();
        new GameProgressionModel().syncPadSurfaces(state);

        nextMonsterId = 1;
        lastStage = 1;

        agent = new AutonomousMonsterMazeAgent(
                new RobustLiveController(
                        new LiveObjectiveController(
                                new MazeAwareRecedingHorizonController(1, AiProfile.HIGH_SKILL))));
    }

    void tick(Action manualAction) {
        if (!state.alive) return;

        spawnStarterBatchIfNeeded();
        Action action = aiEnabled ? aiAction() : manualAction;
        int beforeStage = state.stage;
        simulator.tick(state, action);

        if (state.previewPadRequested && state.previewPadRow < 0) {
            ArrayList<Cell> avoid = currentPadAvoidance();
            Cell preview = pads.nextPad(avoid);
            state.previewPadRow = preview.row();
            state.previewPadColumn = preview.column();
            new GameProgressionModel().syncPadSurfaces(state);
            removeMonstersOnPad(state, preview);
            state.previewPadRequested = false;
        }

        if (state.stage != beforeStage || state.stage != lastStage) {
            int spawned = spawnAdditional(
                    state, monsterRandom, additionalMonsterCount(state.mode));
            state.pendingMonsterSpawns -= spawned;
            if (state.pendingMonsterSpawns < 0) state.pendingMonsterSpawns = 0;

            if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
                Cell active = new Cell(state.activePadRow, state.activePadColumn);
                removeMonstersOnPad(state, active);
                new GameProgressionModel().syncPadSurfaces(state);
            }
            lastStage = state.stage;
        }
    }

    private Action aiAction() {
        boolean allowJump = state.kit != Kit.JUMPER || state.ability.charges > 0;
        return agent.decide(state, allowJump);
    }

    private void spawnStarterBatchIfNeeded() {
        if (state.pendingMonsterSpawns <= 0) return;

        int batch = Math.min(25, state.pendingMonsterSpawns);
        List<Cell> paths = pathCells();
        Cell center = new Cell(49, 49);
        int spawned = 0;
        int guard = 0;
        while (spawned < batch && guard++ < batch * 10) {
            Cell pos = paths.get(monsterRandom.nextInt(paths.size()));
            if (distanceSq(pos, center) < 7.5 * 7.5) continue;
            state.monsters.add(new MonsterState(
                    nextMonsterId++, pos.row() + 0.5, GameState.PATH_Y, pos.column() + 0.5));
            spawned++;
        }
        state.pendingMonsterSpawns -= spawned;
    }

    private int spawnAdditional(GameState state, Random random, int count) {
        List<Cell> spawns = spawnCells();
        if (spawns.isEmpty()) spawns = pathCells();
        for (int i = 0; i < count; i++) {
            Cell pos = spawns.get(random.nextInt(spawns.size()));
            state.monsters.add(new MonsterState(
                    nextMonsterId++, pos.row() + 0.5, GameState.PATH_Y, pos.column() + 0.5));
        }
        return count;
    }

    private int initialMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 225 : 150;
    }

    private int additionalMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 30 : 15;
    }

    private List<Cell> currentPadAvoidance() {
        ArrayList<Cell> avoid = new ArrayList<>();
        for (Cell old : state.oldPads) avoid.add(old);
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            avoid.add(new Cell(state.activePadRow, state.activePadColumn));
        }
        return avoid;
    }

    private void removeMonstersOnPad(GameState state, Cell pad) {
        if (pad == null) return;
        for (MonsterState monster : state.monsters) {
            if (monster.removed) continue;
            double dx = monster.x - (pad.row() + 0.5);
            double dz = monster.z - (pad.column() + 0.5);
            double dy = monster.y - GameState.PAD_SURFACE_Y;
            if (dx > -2.5 && dx < 2.5
                    && dz > -2.5 && dz < 2.5
                    && dy > 0.0 && dy < 5.0) {
                monster.removed = true;
            }
        }
    }

    private List<Cell> pathCells() {
        ArrayList<Cell> out = new ArrayList<>();
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                if (maze.isRawPath(r, c)) out.add(new Cell(r, c));
            }
        }
        return out;
    }

    private List<Cell> spawnCells() {
        ArrayList<Cell> out = new ArrayList<>();
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                if (maze.raw(r, c) == 2) out.add(new Cell(r, c));
            }
        }
        return out;
    }

    private static double distanceSq(Cell a, Cell b) {
        double dr = a.row() - b.row();
        double dc = a.column() - b.column();
        return dr * dr + dc * dc;
    }
}

final class ViewerInput {
    boolean forward;
    boolean back;
    boolean left;
    boolean right;
    boolean sprint;
    boolean jump;
    boolean turnLeft;
    boolean turnRight;
    boolean abilityPulse;

    Action toAction(boolean aiEnabled) {
        double f = 0.0;
        double s = 0.0;
        if (forward) f += 1.0;
        if (back) f -= 1.0;
        if (right) s += 1.0;
        if (left) s -= 1.0;
        float yawDelta = 0.0F;
        if (turnLeft) yawDelta -= 10.0F;
        if (turnRight) yawDelta += 10.0F;
        return new Action(f, s, jump, sprint, yawDelta, abilityPulse);
    }
}

final class ViewerPanel extends JPanel {
    private final Simulation simulation;
    private final ViewerInput input = new ViewerInput();
    private final PerspectiveRenderer renderer = new PerspectiveRenderer();

    private boolean firstPerson;
    private boolean showHitboxes;
    private boolean showVelocity;
    private float cameraYaw;
    private float cameraPitch = -18.0F;
    private double cameraDistance = 9.0;
    private int lastMouseX;
    private int lastMouseY;
    private boolean dragging;

    ViewerPanel(Simulation simulation) {
        this.simulation = simulation;
        setFocusable(true);
        setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        cameraYaw = simulation.state.player.yaw;

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W -> input.forward = true;
                    case KeyEvent.VK_S -> input.back = true;
                    case KeyEvent.VK_A -> input.left = true;
                    case KeyEvent.VK_D -> input.right = true;
                    case KeyEvent.VK_SHIFT -> input.sprint = true;
                    case KeyEvent.VK_SPACE -> input.jump = true;
                    case KeyEvent.VK_LEFT -> input.turnLeft = true;
                    case KeyEvent.VK_RIGHT -> input.turnRight = true;
                    case KeyEvent.VK_E -> input.abilityPulse = true;
                    case KeyEvent.VK_P -> simulation.paused = !simulation.paused;
                    case KeyEvent.VK_F -> {
                        firstPerson = !firstPerson;
                        cameraYaw = simulation.state.player.yaw;
                    }
                    case KeyEvent.VK_H -> showHitboxes = !showHitboxes;
                    case KeyEvent.VK_V -> showVelocity = !showVelocity;
                    case KeyEvent.VK_R -> {
                        simulation.reset();
                        cameraYaw = simulation.state.player.yaw;
                        cameraPitch = firstPerson ? -4.0F : -18.0F;
                    }
                    case KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> simulation.ticksPerFrame = Math.min(8, simulation.ticksPerFrame + 1);
                    case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> simulation.ticksPerFrame = Math.max(1, simulation.ticksPerFrame - 1);
                    case KeyEvent.VK_PERIOD -> {
                        if (simulation.paused) simulation.tick(input.toAction(false));
                        input.abilityPulse = false;
                    }
                    case KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3 -> {
                        simulation.pattern = e.getKeyCode() - KeyEvent.VK_1;
                        simulation.reset();
                        cameraYaw = simulation.state.player.yaw;
                    }
                    case KeyEvent.VK_M -> {
                        simulation.mode = simulation.mode == Mode.MODERN ? Mode.SPEED : Mode.MODERN;
                        simulation.reset();
                        cameraYaw = simulation.state.player.yaw;
                    }
                    case KeyEvent.VK_K -> {
                        Kit[] kits = Kit.values();
                        simulation.kit = kits[(simulation.kit.ordinal() + 1) % kits.length];
                        simulation.reset();
                        cameraYaw = simulation.state.player.yaw;
                    }
                    case KeyEvent.VK_I -> simulation.aiEnabled = !simulation.aiEnabled;
                    case KeyEvent.VK_OPEN_BRACKET -> cameraDistance = Math.max(3.5, cameraDistance - 1.0);
                    case KeyEvent.VK_CLOSE_BRACKET -> cameraDistance = Math.min(20.0, cameraDistance + 1.0);
                }
                repaint();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W -> input.forward = false;
                    case KeyEvent.VK_S -> input.back = false;
                    case KeyEvent.VK_A -> input.left = false;
                    case KeyEvent.VK_D -> input.right = false;
                    case KeyEvent.VK_SHIFT -> input.sprint = false;
                    case KeyEvent.VK_SPACE -> input.jump = false;
                    case KeyEvent.VK_LEFT -> input.turnLeft = false;
                    case KeyEvent.VK_RIGHT -> input.turnRight = false;
                    case KeyEvent.VK_E -> input.abilityPulse = false;
                }
            }
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragging = true;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!dragging) return;
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                lastMouseX = e.getX();
                lastMouseY = e.getY();

                if (firstPerson) {
                    simulation.state.player.yaw = normaliseYaw(simulation.state.player.yaw + dx * 0.45F);
                    cameraYaw = simulation.state.player.yaw;
                } else {
                    cameraYaw = normaliseYaw(cameraYaw + dx * 0.45F);
                }
                cameraPitch = clamp(cameraPitch - dy * 0.30F, -70.0F, 35.0F);
                repaint();
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                cameraDistance = clamp(cameraDistance + e.getPreciseWheelRotation(), 3.5, 20.0);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    Action inputAction() {
        Action action = input.toAction(simulation.aiEnabled);
        input.abilityPulse = false;
        return action;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        renderer.render((Graphics2D) g, getWidth(), getHeight(), simulation,
                firstPerson, cameraYaw, cameraPitch, cameraDistance, showHitboxes, showVelocity);
        drawHud((Graphics2D) g);
    }

    private void drawHud(Graphics2D g) {
        int width = getWidth();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        g.setColor(new Color(0, 0, 0, 175));
        g.fillRoundRect(12, 12, 390, 180, 12, 12);
        g.setColor(Color.WHITE);

        int aliveMobs = 0;
        double maxMobSpeed = 0.0;
        double averageMobSpeed = 0.0;
        for (MonsterState m : simulation.state.monsters) {
            if (m.removed) continue;
            aliveMobs++;
            double speed = Math.hypot(m.lastDx, m.lastDz) * 20.0;
            maxMobSpeed = Math.max(maxMobSpeed, speed);
            averageMobSpeed += speed;
        }
        if (aliveMobs > 0) averageMobSpeed /= aliveMobs;

        String[] lines = {
                "MONSTER MAZE 3D MECHANICS VIEWER",
                String.format(Locale.ROOT, "mode=%s  pattern=%d  kit=%s",
                        simulation.mode, simulation.pattern + 1, simulation.kit),
                String.format(Locale.ROOT, "stage=%d  timer=%.1fs  tick=%d  health=%.1f",
                        simulation.state.stage,
                        simulation.state.phaseTicksRemaining / 20.0,
                        simulation.state.tick,
                        simulation.state.player.health),
                String.format(Locale.ROOT, "mobs=%d  mob_speed=avg %.2f / max %.2f b/s",
                        aliveMobs, averageMobSpeed, maxMobSpeed),
                String.format(Locale.ROOT, "player=(%.2f,%.2f,%.2f) v=(%.2f,%.2f,%.2f)",
                        simulation.state.player.x, simulation.state.player.y, simulation.state.player.z,
                        simulation.state.player.vx, simulation.state.player.vy, simulation.state.player.vz),
                "control=" + (simulation.aiEnabled ? "AI" : "MANUAL")
                        + (simulation.paused ? " / PAUSED" : ""),
                "",
                "WASD move | Shift sprint | Space jump | E ability | ←/→ turn",
                "I AI | P pause | . step | +/- sim speed | 1/2/3 pattern | M mode | K kit",
                "F first-person | mouse camera | [ ] camera distance | H hitboxes | V velocity"
        };
        int y = 32;
        for (String line : lines) {
            g.drawString(line, 24, y);
            y += 17;
        }

        if (!simulation.state.alive) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.setColor(new Color(255, 80, 80));
            String dead = "PLAYER DOWN — press R to reset";
            g.drawString(dead, (width - g.getFontMetrics().stringWidth(dead)) / 2, 70);
        }
    }

    private static float normaliseYaw(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

final class PerspectiveRenderer {
    private static final double FOV_DEGREES = 72.0;
    private static final double MAX_RENDER_DISTANCE = 36.0;

    void render(Graphics2D g, int width, int height, Simulation simulation,
                boolean firstPerson, float cameraYaw, float cameraPitch,
                double cameraDistance, boolean showHitboxes, boolean showVelocity) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        g.setPaint(new GradientPaint(0, 0, new Color(116, 165, 215),
                0, Math.max(1, height), new Color(32, 50, 68)));
        g.fillRect(0, 0, width, height);

        GameState state = simulation.state;
        double targetX = state.player.x;
        double targetY = state.player.y + 0.9;
        double targetZ = state.player.z;

        double camX;
        double camY;
        double camZ;
        if (firstPerson) {
            camX = state.player.x;
            camY = state.player.y + 1.62;
            camZ = state.player.z;
        } else {
            double yaw = Math.toRadians(cameraYaw);
            double pitch = Math.toRadians(cameraPitch);
            camX = targetX - Math.sin(yaw) * Math.cos(pitch) * cameraDistance;
            camY = targetY - Math.sin(pitch) * cameraDistance;
            camZ = targetZ - Math.cos(yaw) * Math.cos(pitch) * cameraDistance;
        }

        Camera camera = new Camera(
                camX, camY, camZ,
                firstPerson ? cameraYaw : cameraYaw,
                cameraPitch,
                width / 2.0,
                height / 2.0 + 20.0,
                height / (2.0 * Math.tan(Math.toRadians(FOV_DEGREES) / 2.0)));

        ArrayList<Face> faces = new ArrayList<>(5000);

        int minRow = Math.max(0, (int) Math.floor(targetX - MAX_RENDER_DISTANCE));
        int maxRow = Math.min(MazeModel.SIZE - 1, (int) Math.ceil(targetX + MAX_RENDER_DISTANCE));
        int minCol = Math.max(0, (int) Math.floor(targetZ - MAX_RENDER_DISTANCE));
        int maxCol = Math.min(MazeModel.SIZE - 1, (int) Math.ceil(targetZ + MAX_RENDER_DISTANCE));

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                if (!state.maze.isPhysicalFloor(row, col)) continue;

                double cx = row + 0.5;
                double cz = col + 0.5;
                double distance = Math.hypot(cx - targetX, cz - targetZ);
                if (distance > MAX_RENDER_DISTANCE) continue;

                double topY = state.maze.hasPadSurface(row, col) ? -0.90 : 0.0;
                double bottomY = topY - 0.18;

                Color blockColor = blockColor(state, row, col);
                addCube(faces, camera, cx - 0.5, bottomY, cz - 0.5,
                        cx + 0.5, topY, cz + 0.5, blockColor);
            }
        }

        // A low "void" surface makes the missing floor visible in perspective.
        addCube(faces, camera,
                0.0, -4.0, 0.0, MazeModel.SIZE, -3.92, MazeModel.SIZE,
                new Color(17, 21, 28));

        for (MonsterState monster : state.monsters) {
            if (monster.removed) continue;
            if (Math.hypot(monster.x - targetX, monster.z - targetZ) > MAX_RENDER_DISTANCE + 5) continue;

            Color body = monster.frozen(state.tick)
                    ? new Color(80, 220, 255)
                    : monster.launched(state.tick)
                    ? new Color(255, 110, 220)
                    : new Color(224, 224, 224);

            addCube(faces, camera,
                    monster.x - 0.35, monster.y, monster.z - 0.35,
                    monster.x + 0.35, monster.y + 1.0, monster.z + 0.35,
                    body);
            addCube(faces, camera,
                    monster.x - 0.29, monster.y + 1.0, monster.z - 0.29,
                    monster.x + 0.29, monster.y + 1.65, monster.z + 0.29,
                    body.brighter());

            if (showHitboxes) addWireBox(g, camera,
                    monster.x - 0.35, monster.y, monster.z - 0.35,
                    monster.x + 0.35, monster.y + 1.8, monster.z + 0.35,
                    new Color(255, 210, 80));
            if (showVelocity) drawVelocity(g, camera,
                    monster.x, monster.y + 0.8, monster.z,
                    monster.vx * 8.0, monster.vy * 8.0, monster.vz * 8.0,
                    new Color(255, 170, 80));
        }

        if (!firstPerson) {
            addCube(faces, camera,
                    state.player.x - 0.30, state.player.y, state.player.z - 0.30,
                    state.player.x + 0.30, state.player.y + 1.8, state.player.z + 0.30,
                    new Color(65, 150, 240));
            addCube(faces, camera,
                    state.player.x - 0.28, state.player.y + 1.8, state.player.z - 0.28,
                    state.player.x + 0.28, state.player.y + 2.2, state.player.z + 0.28,
                    new Color(238, 196, 145));

            if (showHitboxes) addWireBox(g, camera,
                    state.player.x - 0.30, state.player.y, state.player.z - 0.30,
                    state.player.x + 0.30, state.player.y + 1.8, state.player.z + 0.30,
                    new Color(80, 255, 120));
        }

        faces.sort(Comparator.comparingDouble((Face f) -> f.depth).reversed());
        for (Face face : faces) {
            g.setColor(face.color);
            g.fillPolygon(face.polygon);
        }

        if (showVelocity) drawVelocity(g, camera,
                state.player.x, state.player.y + 0.9, state.player.z,
                state.player.vx * 8.0, state.player.vy * 8.0, state.player.vz * 8.0,
                new Color(80, 255, 120));

        drawPadMarkers(g, camera, state);
    }

    private void drawPadMarkers(Graphics2D g, Camera camera, GameState state) {
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            drawWorldRing(g, camera,
                    state.activePadRow + 0.5, -0.84, state.activePadColumn + 0.5,
                    2.45, new Color(255, 220, 70));
        }
        if (state.previewPadRow >= 0 && state.previewPadColumn >= 0) {
            drawWorldRing(g, camera,
                    state.previewPadRow + 0.5, -0.78, state.previewPadColumn + 0.5,
                    2.45, new Color(130, 255, 255));
        }
    }

    private Color blockColor(GameState state, int row, int col) {
        if (state.maze.hasPadSurface(row, col)) return new Color(218, 181, 62);
        int raw = state.maze.raw(row, col);
        if (raw == 5 || raw == 6) return state.maze.isDisabled(row, col)
                ? new Color(116, 92, 92)
                : new Color(116, 116, 126);
        if (raw == 2) return new Color(154, 126, 98);
        if (state.maze.isDisabled(row, col)) return new Color(75, 75, 83);
        return new Color(112, 88, 64);
    }

    private void drawWorldRing(Graphics2D g, Camera camera,
                               double x, double y, double z, double radius, Color color) {
        ArrayList<P2> points = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            double a = 2.0 * Math.PI * i / 28.0;
            P2 p = camera.project(x + Math.cos(a) * radius, y, z + Math.sin(a) * radius);
            if (p != null) points.add(p);
        }
        if (points.size() < 2) return;

        g.setColor(color);
        g.setStroke(new BasicStroke(2.0f));
        for (int i = 1; i < points.size(); i++) {
            g.drawLine((int) points.get(i - 1).x, (int) points.get(i - 1).y,
                    (int) points.get(i).x, (int) points.get(i).y);
        }
    }

    private void drawVelocity(Graphics2D g, Camera camera,
                              double x, double y, double z,
                              double dx, double dy, double dz, Color color) {
        P2 a = camera.project(x, y, z);
        P2 b = camera.project(x + dx, y + dy, z + dz);
        if (a == null || b == null) return;
        g.setColor(color);
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine((int) a.x, (int) a.y, (int) b.x, (int) b.y);
        g.fillOval((int) b.x - 3, (int) b.y - 3, 6, 6);
    }

    private void addWireBox(Graphics2D g, Camera camera,
                            double x0, double y0, double z0,
                            double x1, double y1, double z1,
                            Color color) {
        double[][] v = vertices(x0, y0, z0, x1, y1, z1);
        P2[] p = new P2[8];
        for (int i = 0; i < 8; i++) p[i] = camera.project(v[i][0], v[i][1], v[i][2]);
        for (int[] e : BOX_EDGES) {
            if (p[e[0]] == null || p[e[1]] == null) continue;
            g.setColor(color);
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine((int) p[e[0]].x, (int) p[e[0]].y,
                    (int) p[e[1]].x, (int) p[e[1]].y);
        }
    }

    private void addCube(List<Face> faces, Camera camera,
                         double x0, double y0, double z0,
                         double x1, double y1, double z1,
                         Color color) {
        double[][] v = vertices(x0, y0, z0, x1, y1, z1);
        int[][] faceIndices = {
                {0, 1, 2, 3},
                {4, 7, 6, 5},
                {0, 4, 5, 1},
                {1, 5, 6, 2},
                {2, 6, 7, 3},
                {3, 7, 4, 0}
        };
        double[] shade = {0.68, 1.00, 0.84, 0.76, 0.70, 0.80};
        for (int f = 0; f < faceIndices.length; f++) {
            int[] idx = faceIndices[f];
            java.awt.Polygon polygon = new java.awt.Polygon();
            double depth = 0.0;
            boolean valid = true;
            for (int i : idx) {
                P2 p = camera.project(v[i][0], v[i][1], v[i][2]);
                if (p == null) {
                    valid = false;
                    break;
                }
                polygon.addPoint((int) Math.round(p.x), (int) Math.round(p.y));
                depth += p.depth;
            }
            if (!valid) continue;
            faces.add(new Face(polygon, depth / idx.length, shade(color, shade[f])));
        }
    }

    private static Color shade(Color color, double factor) {
        return new Color(
                clampColor((int) Math.round(color.getRed() * factor)),
                clampColor((int) Math.round(color.getGreen() * factor)),
                clampColor((int) Math.round(color.getBlue() * factor)));
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static double[][] vertices(double x0, double y0, double z0,
                                       double x1, double y1, double z1) {
        return new double[][]{
                {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}
        };
    }

    private static final int[][] BOX_EDGES = {
            {0,1},{1,2},{2,3},{3,0},
            {4,5},{5,6},{6,7},{7,4},
            {0,4},{1,5},{2,6},{3,7}
    };
}

record Camera(double x, double y, double z, float yawDegrees, float pitchDegrees,
              double screenX, double screenY, double focalLength) {
    P2 project(double wx, double wy, double wz) {
        double dx = wx - x;
        double dy = wy - y;
        double dz = wz - z;

        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);

        double fx = Math.sin(yaw) * Math.cos(pitch);
        double fy = Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);

        double rx = Math.cos(yaw);
        double ry = 0.0;
        double rz = -Math.sin(yaw);

        double ux = fy * rz - fz * ry;
        double uy = fz * rx - fx * rz;
        double uz = fx * ry - fy * rx;

        double camX = dx * rx + dy * ry + dz * rz;
        double camY = dx * ux + dy * uy + dz * uz;
        double camZ = dx * fx + dy * fy + dz * fz;

        if (camZ <= 0.18) return null;

        double sx = screenX + camX * focalLength / camZ;
        double sy = screenY - camY * focalLength / camZ;
        return new P2(sx, sy, camZ);
    }
}

record P2(double x, double y, double depth) {}
record Face(java.awt.Polygon polygon, double depth, Color color) {}
