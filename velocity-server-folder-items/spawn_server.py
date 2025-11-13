#!/usr/bin/env python3
import os, sys, zipfile, shutil, subprocess, argparse, pathlib
import tempfile, time  # tempfile/time kept in case you want to reuse later

WORLD_DIRS = {"world", "world_nether", "world_the_end"}
HERE = pathlib.Path(__file__).resolve().parent  # script dir
CWD  = pathlib.Path().resolve()                 # process cwd


def setprop(lines, key, val):
    # keep 'lines' as "no newline" entries, add/replace cleanly
    needle = key + "="
    for idx, line in enumerate(lines):
        if line.startswith(needle):
            lines[idx] = f"{key}={val}"
            return
    lines.append(f"{key}={val}")


def read_props(path):
    p = pathlib.Path(path)
    if p.exists():
        return p.read_text(encoding="utf-8").splitlines()
    return []


def write_props(path, lines):
    pathlib.Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def inflate_from_zip(zip_path: pathlib.Path, dest_dir: pathlib.Path):
    """
    Unzip the template fresh into dest_dir every time.
    Any existing dest_dir will be removed first.
    """
    if not zip_path.exists():
        print(f"ERROR: template zip not found: {zip_path}", file=sys.stderr)
        sys.exit(2)

    if dest_dir.exists():
        shutil.rmtree(dest_dir, ignore_errors=True)

    dest_dir.mkdir(parents=True, exist_ok=True)
    print(f"[*] Extracting template {zip_path} -> {dest_dir}")

    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(dest_dir)

    print("[OK] Extraction complete.")
    return "unzip"


def clear_worlds(dest_dir: pathlib.Path):
    for world in WORLD_DIRS:
        p = dest_dir / world
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description="Spawn per-user Paper server by unzipping a template each time.")
    ap.add_argument("uuid", help="player UUID or 'warm'")
    ap.add_argument("port", type=int, help="server port")
    ap.add_argument("--rcon-port", type=int, default=None)
    ap.add_argument("--rcon-pass", type=str, default=None)
    ap.add_argument("--template", default="voxelearth.zip", help="server template zip (unzipped every run)")
    # kept for CLI compatibility; no longer used for caching
    ap.add_argument("--base-dir", default="templates/voxelearth-base", help="(unused) legacy base dir option")
    ap.add_argument("--jar", default="paper.jar")
    ap.add_argument("--java", default="java")
    ap.add_argument("--xms", default=None)
    ap.add_argument("--xmx", default=None)
    ap.add_argument("--empty-world", action="store_true", help="start with no world folders; Paper generates fresh")
    args = ap.parse_args()

    # Resolve everything to absolute paths
    template = (CWD / args.template).resolve()
    servers_root = (CWD / "servers").resolve()

    uuid = args.uuid
    port = int(args.port)
    server_name = uuid[:8] if uuid != "warm" else f"warm-{os.getpid():x}"
    folder = (servers_root / f"voxelearth-{server_name}").resolve()

    print(f"[=] CWD     : {CWD}")
    print(f"[=] Template: {template}")
    print(f"[=] Servers : {servers_root}")
    print(f"[=] Folder  : {folder}")

    servers_root.mkdir(parents=True, exist_ok=True)

    # Always unzip template fresh for this server
    mode = inflate_from_zip(template, folder)

    if args.empty_world:
        clear_worlds(folder)

    # ---- server.properties ----
    prop_path = folder / "server.properties"
    props = read_props(prop_path)

    setprop(props, "server-port", str(port))
    if args.rcon_port is not None and args.rcon_pass:
        setprop(props, "enable-rcon", "true")
        setprop(props, "rcon.port", str(args.rcon_port))
        setprop(props, "rcon.password", args.rcon_pass)

    setprop(props, "view-distance", "32")
    setprop(props, "simulation-distance", "6")

    # **CRITICAL**: ensure online-mode is true before starting server
    setprop(props, "online-mode", "false")

    write_props(prop_path, props)
    print(f"[OK] Wrote {prop_path} (online-mode=true enforced)")

    # ---- eula.txt ----
    eula_path = folder / "eula.txt"
    if not eula_path.exists():
        eula_path.write_text("eula=true\n", encoding="utf-8")
        print(f"[OK] Accepted EULA at {eula_path}")

    # ---- cuda_voxelizer permissions ----
    cuda_path = folder / "cuda_voxelizer"
    if cuda_path.exists() and os.name != "nt":
        try:
            subprocess.run(["chmod", "777", str(cuda_path)], check=False)
        except Exception:
            pass

    # --- Flip Paper global: proxies.velocity.enabled -> true ---
    pg = folder / "config/paper-global.yml"
    if pg.exists():
        import re
        text = pg.read_text(encoding="utf-8")
        new_text = re.sub(
            r"(?m)(^(\s*)velocity:\s*\n\2[ \t]+enabled:\s*)false\b",
            r"\1true",
            text,
        )
        if new_text != text:
            pg.write_text(new_text, encoding="utf-8")
            print(f"[OK] Enabled Velocity support in {pg}")
        else:
            print(f"[=] Velocity already enabled in {pg}")
    else:
        print(f"[!] {pg} not found; skipping Velocity enable toggle")

    # sanity checks before boot
    jar_path = folder / args.jar
    if not jar_path.exists():
        print(f"ERROR: {jar_path} is missing. Does your template include {args.jar}?", file=sys.stderr)
        sys.exit(3)

    # write a per-server log so you can inspect boot
    log_path = folder / "server.log"

    java_cmd = [args.java]
    if args.xms:
        java_cmd.append(f"-Xms{args.xms}")
    if args.xmx:
        java_cmd.append(f"-Xmx{args.xmx}")
    java_cmd += [
        "-XX:+UseG1GC",
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
        "-Daikars.new.flags=true",
        "-jar",
        args.jar,
        "--nogui",
    ]

    print(f"[*] Launching Paper: {' '.join(java_cmd)}  (cwd={folder})")
    with open(log_path, "ab", buffering=0) as logf:
        subprocess.Popen(java_cmd, cwd=str(folder), stdout=logf, stderr=subprocess.STDOUT)

    msg = f"Spawned {folder} via {mode} on port {port}"
    if args.rcon_port is not None and args.rcon_pass:
        msg += f" with RCON {args.rcon_port}"
    print(msg, flush=True)


if __name__ == "__main__":
    main()
