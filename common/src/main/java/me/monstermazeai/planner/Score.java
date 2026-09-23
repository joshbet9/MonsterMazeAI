package me.monstermazeai.planner;

public record Score(double value, boolean alive, double health, double padDistance, double monsterExposure) implements Comparable<Score> {
    @Override public int compareTo(Score other){return Double.compare(value, other.value);}
}