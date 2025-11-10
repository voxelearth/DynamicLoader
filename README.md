> **To Do List**
>
> Players should be OP when they go to their personal Earths
>
> View distance stuck at 6 and not 32 in personal worlds
>
> Bug: Interacting with anything opens the Navigator
>
> Feel free to open a pull request patching any of these!

# Voxel Earth — DynamicLoader (Velocity)
**VoxelEarth monorepo with consistent versions:** https://github.com/ryanhlewis/VoxelEarth

A **Velocity** proxy plugin that spins Minecraft servers up/down on demand for Voxel Earth.

## Auto-setup (recommended)
**Just want a Voxel Earth server? Set up easily! (Needs WSL2/NVIDIA GPU)**
```bash
git clone https://github.com/voxelearth/dynamicloader
cd dynamicloader
./setup.sh
```
This script provisions a Velocity instance, a lobby, and wires in this plugin for a quick test drive.

## Build from source
```bash
./gradlew build
# Output jar:
# ./build/libs/*.jar
```

## Install into Velocity
1. Copy the built jar to your Velocity server’s `plugins/` directory.
2. Copy the contents of `velocity-server-folder-items/` **next to** the Velocity jar (the plugin expects these helpers at runtime).

## Run
Start Velocity as usual. DynamicLoader will bring backend servers up when players request regions and tear them down when idle.

## Useful files & dirs
- `velocity-server-folder-items/` — helper files the proxy expects at runtime.
- `src/main/java/...` — plugin implementation.

## Acknowledgements
- **Velocity** — the proxy platform.
- Broader Voxel Earth pipeline & upstream authors:
  **ForceFlow** (cuda_voxelizer & TriMesh2), **Lucas Dower** (ObjToSchematic),
  **Cesium / Google** (3D Tiles), **Omar Shehata** (viewer inspiration).
