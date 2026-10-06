        double bestScore = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestClosing = 0.0D;
        double bestInterceptTicks = Double.POSITIVE_INFINITY;
        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double dx = monster.x - state.player.x;
            double dz = monster.z - state.player.z;
            double distance = Math.hypot(dx, dz);
            if (distance < 0.05D) continue;

            double along = dx * routeDirRow + dz * routeDirColumn;
            if (along <= 0.0D || along > 3.75D) continue;

            double lateral = Math.abs(dx * routeDirColumn - dz * routeDirRow);
            if (lateral > 1.25D) continue;

            /*
             * Predict relative player/mob motion rather than treating the
             * player as stationary. This gives the controller a short warning
             * window for fast closing mobs without making stationary mobs
             * outside the original contact envelope expensive.
             */
            double relativeVx = monster.vx - state.player.vx;
            double relativeVz = monster.vz - state.player.vz;
            double closing = -(relativeVx * dx + relativeVz * dz) / distance;

            boolean immediate = distance <= 2.15D;
            double interceptTicks = Double.POSITIVE_INFINITY;
            boolean predictive = false;
            if (closing > 0.03D) {
                interceptTicks = distance / closing;
                predictive = interceptTicks <= 8.0D;
            }
            if (!immediate && !predictive) continue;

            /*
             * Keep the prediction inside the active route corridor. A mob
             * moving laterally away should not trigger a large early dodge.
             */
            double predictionTicks = predictive
                    ? interceptTicks
                    : Math.min(4.0D, Math.max(0.0D, interceptTicks));
            double futureDx = dx + relativeVx * predictionTicks;
            double futureDz = dz + relativeVz * predictionTicks;
            double futureAlong = futureDx * routeDirRow + futureDz * routeDirColumn;
            double futureLateral = Math.abs(
                    futureDx * routeDirColumn - futureDz * routeDirRow);
            if (futureAlong < -0.35D || futureAlong > 2.50D || futureLateral > 1.10D) {
                continue;
            }

            double score = Math.min(distance, interceptTicks)
                    - 0.25D * Math.max(0.0D, closing);
            if (score < bestScore) {
                bestScore = score;
                bestDistance = distance;
                bestClosing = closing;
                bestInterceptTicks = interceptTicks;
                threat = monster;
            }
        }

        if (threat == null) return null;

        int sideRow = routeDirColumn;
