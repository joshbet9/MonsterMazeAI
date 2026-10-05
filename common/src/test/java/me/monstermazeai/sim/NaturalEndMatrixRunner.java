package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class NaturalEndMatrixRunner {
    private NaturalEndMatrixRunner() {}

    public static void main(String[] args) throws Exception {
        List<Case> cases = new ArrayList<>();
        for (Mode mode : new Mode[]{Mode.SPEED, Mode.MODERN}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    cases.add(new Case(mode, pattern, kit));
                }
            }
        }

        int workers = Math.min(3, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Case c : cases) {
                futures.add(executor.submit(() -> {
                    long start = System.nanoTime();
                    AuthenticStage10SimulationTest.RunResult r =
                            AuthenticStage10SimulationTest.runNaturalEnd(
                                    c.pattern, c.kit, AiProfile.HIGH_SKILL, c.mode);
                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    return new Result(c, r, seconds);
                }));
            }

            double speedSum = 0.0;
            double modernSum = 0.0;
            int speedCount = 0;
            int modernCount = 0;

            System.out.println("NATURAL_END_MATRIX");
            System.out.println("mode pattern kit stage ticks health x z wallSeconds");
            for (Future<Result> future : futures) {
                Result result = future.get();
                AuthenticStage10SimulationTest.RunResult r = result.run;
                System.out.printf(java.util.Locale.ROOT,
                        "%s %d %s %d %d %.1f %.3f %.3f %.1f%n",
                        result.caseInfo.mode, result.caseInfo.pattern + 1, result.caseInfo.kit,
                        r.maxStage(), r.ticks(), r.health(), r.x(), r.z(), result.seconds);
                if (result.caseInfo.mode == Mode.SPEED) {
                    speedSum += r.maxStage();
                    speedCount++;
                } else {
                    modernSum += r.maxStage();
                    modernCount++;
                }
            }

            System.out.printf(java.util.Locale.ROOT,
                    "AVERAGE SPEED %.2f%n", speedSum / speedCount);
            System.out.printf(java.util.Locale.ROOT,
                    "AVERAGE MODERN %.2f%n", modernSum / modernCount);
            System.out.println("END NATURAL_END_MATRIX");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private record Case(Mode mode, int pattern, Kit kit) {}
    private record Result(Case caseInfo, AuthenticStage10SimulationTest.RunResult run, double seconds) {}
}
