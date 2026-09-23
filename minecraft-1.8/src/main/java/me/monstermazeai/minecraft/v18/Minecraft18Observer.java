package me.monstermazeai.minecraft.v18;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class Minecraft18Observer {
    private static final Minecraft MC = Minecraft.getMinecraft();

    private int ticksSinceLastLog;

    public void tick() {
        if (MC.theWorld == null || MC.thePlayer == null) {
            return;
        }

        ticksSinceLastLog++;

        if (ticksSinceLastLog < 20) {
            return;
        }

        ticksSinceLastLog = 0;

        Observation observation = observe();

        if (!observation.inMonsterMaze) {
            return;
        }

        System.out.println("[MonsterMazeAI/1.8] " + observation.toLogLine());
    }

    public Observation observe() {
        EntityPlayerSP player = MC.thePlayer;
        World world = MC.theWorld;

        ScoreboardSnapshot scoreboard = readScoreboard(world);

        int snowmen = 0;
        List<String> monsterPositions = new ArrayList<String>();

        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntitySnowman)) {
                continue;
            }

            snowmen++;

            if (monsterPositions.size() < 16) {
                monsterPositions.add(String.format(
                        Locale.ROOT,
                        "(%.2f,%.2f,%.2f)",
                        entity.posX,
                        entity.posY,
                        entity.posZ
                ));
            }

            if (snowmen >= 256) {
                break;
            }
        }

        BlockPos nearestBeacon = findNearestBeacon(world, player, 70);

        boolean scoreboardDetected = scoreboard.looksLikeMonsterMaze();
        boolean beaconDetected = nearestBeacon != null;
        boolean monsterDetected = snowmen > 0;

        return new Observation(
                scoreboardDetected || beaconDetected || monsterDetected,
                player,
                scoreboard,
                nearestBeacon,
                snowmen,
                monsterPositions
        );
    }

    private BlockPos findNearestBeacon(World world, EntityPlayerSP player, int radius) {
        int px = player.getPosition().getX();
        int py = player.getPosition().getY();
        int pz = player.getPosition().getZ();

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = py - 4; y <= py + 4; y++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (world.getBlockState(pos).getBlock() != net.minecraft.init.Blocks.beacon) {
                        continue;
                    }

                    double distance = player.getDistanceSq(
                            x + 0.5,
                            y + 0.5,
                            z + 0.5
                    );

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    private ScoreboardSnapshot readScoreboard(World world) {
        Scoreboard scoreboard = world.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);

        if (objective == null) {
            return ScoreboardSnapshot.empty();
        }

        List<ScoreLine> lines = new ArrayList<ScoreLine>();
        Collection<Score> scores = scoreboard.getSortedScores(objective);

        for (Score score : scores) {
            if (score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                continue;
            }

            String name = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(name);

            String formatted = team == null
                    ? name
                    : ScorePlayerTeam.formatPlayerName(team, name);

            lines.add(new ScoreLine(
                    stripFormatting(formatted),
                    score.getScorePoints()
            ));
        }

        String title = stripFormatting(objective.getDisplayName());

        return new ScoreboardSnapshot(title, lines);
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\u00a7.", "");
    }

    public static final class Observation {
        public final boolean inMonsterMaze;
        public final double x;
        public final double y;
        public final double z;
        public final double vx;
        public final double vy;
        public final double vz;
        public final float yaw;
        public final float pitch;
        public final boolean grounded;
        public final float health;
        public final float maxHealth;
        public final ScoreboardSnapshot scoreboard;
        public final BlockPos nearestBeacon;
        public final int monsterCount;
        public final List<String> monsterPositions;

        private Observation(
                boolean inMonsterMaze,
                EntityPlayerSP player,
                ScoreboardSnapshot scoreboard,
                BlockPos nearestBeacon,
                int monsterCount,
                List<String> monsterPositions) {

            this.inMonsterMaze = inMonsterMaze;
            this.x = player.posX;
            this.y = player.posY;
            this.z = player.posZ;
            this.vx = player.motionX;
            this.vy = player.motionY;
            this.vz = player.motionZ;
            this.yaw = player.rotationYaw;
            this.pitch = player.rotationPitch;
            this.grounded = player.onGround;
            this.health = player.getHealth();
            this.maxHealth = player.getMaxHealth();
            this.scoreboard = scoreboard;
            this.nearestBeacon = nearestBeacon;
            this.monsterCount = monsterCount;
            this.monsterPositions = monsterPositions;
        }

        public String toLogLine() {
            return String.format(
                    Locale.ROOT,
                    "player=(%.2f,%.2f,%.2f) " +
                            "vel=(%.3f,%.3f,%.3f) " +
                            "yaw=%.1f pitch=%.1f " +
                            "grounded=%s " +
                            "hp=%.1f/%.1f " +
                            "beacon=%s " +
                            "monsters=%d " +
                            "monsterPos=%s " +
                            "scoreboardTitle=\"%s\" " +
                            "scoreboard=%s",
                    x, y, z,
                    vx, vy, vz,
                    yaw, pitch,
                    grounded,
                    health, maxHealth,
                    nearestBeacon == null ? "none" : nearestBeacon.toString(),
                    monsterCount,
                    monsterPositions.toString(),
                    scoreboard.title,
                    scoreboard.lines.toString()
            );
        }
    }

    public static final class ScoreboardSnapshot {
        public final String title;
        public final List<ScoreLine> lines;

        private ScoreboardSnapshot(String title, List<ScoreLine> lines) {
            this.title = title;
            this.lines = lines;
        }

        static ScoreboardSnapshot empty() {
            return new ScoreboardSnapshot("", new ArrayList<ScoreLine>());
        }

        boolean looksLikeMonsterMaze() {
            String lowerTitle = title.toLowerCase(Locale.ROOT);

            if (lowerTitle.contains("monster maze")) {
                return true;
            }

            for (ScoreLine line : lines) {
                String text = line.text.toLowerCase(Locale.ROOT);

                if (text.contains("safe pad") ||
                        text.equals("stage") ||
                        text.contains("monster maze")) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public String toString() {
            return "{" +
                    "title='" + title + '\'' +
                    ", lines=" + lines +
                    '}';
        }
    }

    public static final class ScoreLine {
        public final String text;
        public final int score;

        private ScoreLine(String text, int score) {
            this.text = text;
            this.score = score;
        }

        @Override
        public String toString() {
            return "'" + text + "'=" + score;
        }
    }
}
