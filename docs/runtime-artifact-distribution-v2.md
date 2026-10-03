# CPU Runtime Artifact Distribution v2

## Principle

MonsterMazeAI owns training and model creation.

MonsterMaze owns the live game.

The 1.8 plugin consumes a versioned runtime artifact produced by MonsterMazeAI. The live server never imports the training repository as source and never contacts a training service.

## Artifact contents

One runtime artifact contains:

- dependency-free Java 8 policy runtime;
- policy model weights;
- schema version;
- feature manifest;
- profile manifest;
- action-head manifest;
- normalization constants;
- model checksum;
- model metadata.

The artifact is immutable after publication.

## Build flow

MonsterMazeAI:

training -> evaluation -> promotion -> runtime artifact publication.

MonsterMaze:

pinned runtime artifact -> bundled into the plugin release.

The plugin release records the runtime artifact version/checksum so a server can identify exactly which policy it is using.

## Local development

For local development, the current promoted artifact can be installed into the developer's Maven repository or consumed from the pinned development location.

This is a build-time dependency only.

The final plugin JAR should contain everything needed to run CPU inference.

## Release safety

A model cannot be packaged just because training completed.

Packaging requires:

1. schema validation;
2. holdout evaluation;
3. challenge-set evaluation;
4. full matrix evaluation;
5. runtime performance test;
6. successful Engine production-emulation run;
7. successful 1.8 integration run.

## Compatibility

The plugin checks the artifact schema version at startup.

Unsupported feature/action/profile schemas fail closed with a clear error.

The plugin does not silently adapt an incompatible artifact.

## Rollback

The previous promoted runtime artifact remains available.

A server administrator can select a known-good runtime artifact version without retraining anything.

Model rollback and code rollback are separate operations.
