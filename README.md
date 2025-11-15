> **To Do List**
>
> Bug: Interacting with anything opens the Navigator
>
> Feel free to open a pull request patching any of these!

# Voxel Earth — DynamicLoader (Velocity)
**VoxelEarth monorepo with consistent versions:** https://github.com/ryanhlewis/VoxelEarth

A **Velocity** proxy plugin that spins Minecraft servers up/down on demand for Voxel Earth.

## Auto-setup (recommended)
**Just want a Voxel Earth server? Set up easily!**
Linux (recommended, faster):
```bash
git clone https://github.com/voxelearth/dynamicloader
cd dynamicloader
./setup.sh
```
This script provisions a Velocity instance, a lobby, and wires in this plugin for a quick test drive.

Just want a single server and not the whole Velocity multi world?
Linux:
```
git clone https://github.com/voxelearth/dynamicloader
cd dynamicloader
./setupsingle.sh
```
Windows:
```
git clone https://github.com/voxelearth/dynamicloader
cd dynamicloader
./setupsingle.bat
```

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

## Docker options

### Build the full DynamicLoader network locally
Use this path when you want the Velocity proxy, lobby, and on-demand subservers exactly as this repo defines them.

```bash
git clone https://github.com/voxelearth/dynamicloader.git
cd dynamicloader
docker build -t dynamicloader .
docker run -it --rm -p 25565:25565 -p 25566:25566 dynamicloader
```

The image runs `setup.sh` during build, then launches both the lobby Paper server and the Velocity proxy when you `docker run` it. Any edits you make locally are picked up on the next `docker build`.

### Build straight from GitHub
If you prefer to skip cloning altogether, let Docker pull the repo as the build context:

```bash
docker build -t dynamicloader https://github.com/voxelearth/dynamicloader.git
docker run -it --rm -p 25565:25565 -p 25566:25566 dynamicloader
```

### Just want a single Voxel Earth Paper server?
The full DynamicLoader network is overkill if you only need one Paper instance with the Voxel Earth plugin preloaded. Use the upstream single-server project instead:

```bash
docker build -t voxelearth https://github.com/ryanhlewis/voxelearth.git
docker run -it --rm -p 25565:25565 voxelearth
```

That repository contains the stand-alone Paper build tailored for single worlds, without the Velocity proxy or warm-pool orchestration.
