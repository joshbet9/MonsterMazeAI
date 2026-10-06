package me.monstermazeai.adapter;

/**
 * Version-specific execution boundary. The AI core never touches Minecraft
 * input APIs; a client adapter implements this interface.
 */
public interface ActionSink {
    void apply(LegacyAction action);
    void releaseAll();
}
