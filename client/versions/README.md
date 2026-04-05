Each directory in this folder is a thin Minecraft-version overlay.

- `version.properties` defines the dependency/build knobs for that Minecraft target.
- `src/client/java` can override shared client classes for a specific version.
- `src/client/resources` can override shared client resources for a specific version.
- `src/main/resources` can override shared metadata/resources for a specific version.

The shared runtime code lives in `src/core/java`, and shared Fabric/Minecraft code lives in `src/shared/client/java`.
