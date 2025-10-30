#!/usr/bin/env python3
import os, sys, zipfile, shutil, subprocess, time, argparse, pathlib

def setprop(lines, key, val):
    """Ensure key=val exists (replace if present, append if missing)."""
    needle = key + "="
    for i, line in enumerate(lines):
        if line.startswith(needle):
            lines[i] = f"{key}={val}\n"
            return
    lines.append(f"{key}={val}\n")

def read_props(path):
    if os.path.exists(path):
        return pathlib.Path(path).read_text(encoding="utf-8").splitlines(keepends=False)
    return []

def write_props(path, lines):
    pathlib.Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")

def main():
    ap = argparse.ArgumentParser(description="Spawn per-user Paper server from template.")
    ap.add_argument("uuid", help="player UUID")
    ap.add_argument("port", type=int, help="server port")
    ap.add_argument("--rcon-port", type=int, default=None, help="enable RCON on this port")
    ap.add_argument("--rcon-pass", type=str, default=None, help="RCON password")
    ap.add_argument("--template", default="voxelearth.zip", help="path to server template zip")
    ap.add_argument("--jar", default="paper.jar", help="server jar inside the template folder")
    ap.add_argument("--java", default="java", help="java executable")
    ap.add_argument("--xms", default=None, help="Xms memory (e.g. 1G)")
    ap.add_argument("--xmx", default=None, help="Xmx memory (e.g. 2G)")
    args = ap.parse_args()

    uuid = args.uuid
    port = int(args.port)
    folder = f"servers/voxelearth-{uuid[:8]}"
    os.makedirs(os.path.dirname(folder), exist_ok=True)

    # Clean old folder if exists
    if os.path.exists(folder):
        shutil.rmtree(folder)

    # Unzip template
    with zipfile.ZipFile(args.template, "r") as zip_ref:
        zip_ref.extractall(folder)

    # server.properties update
    prop_path = os.path.join(folder, "server.properties")
    lines = read_props(prop_path)

    # Required: set server port
    setprop(lines, "server-port", str(port))

    # Optional: enable RCON if both provided
    if args.rcon_port is not None and args.rcon_pass:
        setprop(lines, "enable-rcon", "true")
        setprop(lines, "rcon.port", str(args.rcon_port))
        setprop(lines, "rcon.password", args.rcon_pass)
        # (Optional) keep query off by default; uncomment if you want it
        # setprop(lines, "enable-query", "true")
        # setprop(lines, "query.port", str(port))

    write_props(prop_path, lines)

    # Accept EULA if not present in template
    eula_path = os.path.join(folder, "eula.txt")
    if not os.path.exists(eula_path):
        pathlib.Path(eula_path).write_text("eula=true\n", encoding="utf-8")

    # chmod helper (best-effort, no-op on Windows)
    cuda_path = os.path.join(folder, "cuda_voxelizer")
    if os.path.exists(cuda_path):
        try:
            if os.name != "nt":
                subprocess.run(["chmod", "777", cuda_path], check=False)
        except Exception as e:
            print(f"Warning: chmod failed for {cuda_path}: {e}", flush=True)
    else:
        print(f"Warning: cuda_voxelizer not found in {folder}", flush=True)

    # Build java command
    java_cmd = [args.java]
    if args.xms: java_cmd += [f"-Xms{args.xms}"]
    if args.xmx: java_cmd += [f"-Xmx{args.xmx}"]
    # java -Xms512M -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=30 -XX:G1HeapRegionSize=4M -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=20 -XX:G1MixedGCLiveThresholdPercent=85 -XX:MaxTenuringThreshold=1 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem -Daikars.new.flags=true -jar paper.jar --nogui
    java_cmd += ["-XX:+UseG1GC",
                 "-XX:+ParallelRefProcEnabled",
                 "-XX:MaxGCPauseMillis=100",
                 "-XX:+UnlockExperimentalVMOptions",
                 "-XX:G1NewSizePercent=20",
                 "-XX:G1MaxNewSizePercent=30",
                 "-XX:G1HeapRegionSize=4M",
                 "-XX:G1ReservePercent=15",
                 "-XX:InitiatingHeapOccupancyPercent=20",
                 "-XX:G1MixedGCLiveThresholdPercent=85",
                 "-XX:MaxTenuringThreshold=1",
                 "-XX:+DisableExplicitGC",
                 "-XX:+AlwaysPreTouch",
                 "-XX:+PerfDisableSharedMem",
                 "-Daikars.new.flags=true"]
    java_cmd += ["-jar", args.jar, "--nogui"]

    # Start Paper server
    subprocess.Popen(
        java_cmd,
        cwd=folder,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT
    )

    # Give it time to boot a bit (proxy will still show its own loading bar)
    time.sleep(10)
    msg = f"Started {folder} on port {port}"
    if args.rcon_port is not None and args.rcon_pass:
        msg += f" with RCON on {args.rcon_port}"
    print(msg, flush=True)

if __name__ == "__main__":
    main()
