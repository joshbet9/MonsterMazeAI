            beam = next;
            if (beam.isEmpty()) return routeFollowerAction(source, route, waypoint);
        }

        return beam.get(0).actions.isEmpty()
                ? routeFollowerAction(source, route, waypoint)
                : beam.get(0).actions.get(0);
    }

    private long tacticalRank(GameState state, PlayerRoute route, int waypoint,
                              Cell goal, boolean regionGoal, int regionRadius) {
        if (goalReached(state, route, waypoint, goal, regionGoal, regionRadius)) return 0L;

        long remaining = Math.max(0, route.size() - 1L - waypoint);
        long distance = Math.min(999_999L,
                Math.round(distanceToWaypoint(state, route, waypoint) * 1000));

        /*
         * A normal monster contact removes four health. The tactical branch
         * should therefore strongly prefer clean progress over a small shortcut
         * that buys it with damage. This is still a local heuristic: the actual
         * source bump and health changes remain authoritative in step().
         */
        long damage = Math.min(999_999L,
                Math.round(state.player.damageTaken * 1000));

        /*
         * One damage point is worth roughly ten blocks of route progress in
         * this local ranking. That is intentionally conservative: emergency
         * deliberate bumps are handled by MobInteractionDecision outside this
         * tactical beam rather than being smuggled in as a "good" branch.
         */
        final long DAMAGE_WEIGHT = 50_000_000L;
        final long REMAINING_WEIGHT = 1_000_000L;

        return damage * DAMAGE_WEIGHT
                + remaining * REMAINING_WEIGHT
                + distance;
    }

    private boolean needsTacticalSearch(GameState state) {
        int horizon = tacticalHorizon(state);
        double playerReach = 0.45 + Math.hypot(state.player.vx, state.player.vz) * horizon;
        double contactReach = MonsterMazeBumpModel.CONTACT_DISTANCE + playerReach;

        for (var m : state.monsters) {
            if (m.removed || m.launched(state.tick) || m.frozen(state.tick)
                    || !MonsterRelevance.withinPlayerRadius(m, state.player, TACTICAL_RELEVANCE_RADIUS)) continue;
            double separationSq = sq(state.player.x - m.x)
                    + sq(state.player.y - m.y)
                    + sq(state.player.z - m.z);
            double monsterReach = Math.hypot(m.vx, m.vz) * horizon;
            double threshold = contactReach + monsterReach;
            if (separationSq <= threshold * threshold) return true;
        }

        // Source ability range is six blocks and is therefore already contained
        // by the local interaction envelope. No distant monster can wake the