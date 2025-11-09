#!/usr/bin/env python3
import os, sys, zipfile, shutil, subprocess, argparse, pathlib
import tempfile, time

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

def ensure_base_from_zip(zip_path: pathlib.Path, base_dir: pathlib.Path):
    base_dir.mkdir(parents=True, exist_ok=True)
    ready = base_dir / ".base.ready"
    lock  = base_dir / ".base.lock"

    # Fast path: someone already prepared it
    try:
        if ready.exists() and any(base_dir.iterdir()):
            print(f"[=] Base exists: {base_dir}")
            return
    except StopIteration:
        pass

    # Try to become the preparer
    got_lock = False
    while not got_lock:
        try:
            # O_CREAT|O_EXCL semantics via open with 'x'
            with open(lock, "x") as lf:
                lf.write(str(os.getpid()))
            got_lock = True
        except FileExistsError:
            # Another process is preparing; wait for ready marker
            for _ in range(240):  # up to ~60s
                if ready.exists():
                    print(f"[=] Base prepared by another process: {base_dir}")
                    return
                time.sleep(0.25)
            # If we timed out, loop and try to acquire again (maybe the other died)
    try:
        # Double-check after taking the lock
        if ready.exists() and any(base_dir.iterdir()):
            print(f"[=] Base exists (post-lock): {base_dir}")
            return

        print(f"[*] Inflating template once to {base_dir} ...")
        if not zip_path.exists():
            print(f"ERROR: template zip not found: {zip_path}", file=sys.stderr)
            sys.exit(2)

        # Extract to a temp dir, then atomically move into place
        with tempfile.TemporaryDirectory(dir=base_dir.parent) as tmp:
            tmpdir = pathlib.Path(tmp) / (base_dir.name + ".tmp")
            tmpdir.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(zip_path, "r") as zf:
                for m in zf.infolist():
                    target = tmpdir / m.filename
                    if m.is_dir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with zf.open(m, "r") as src, open(target, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        # preserve timestamp (best effort)
                        date_time = m.date_time + (0, 0, -1)
                        try: os.utime(target, (time.time(), time.mktime(date_time)))
                        except Exception: pass

            # If base dir is empty, move it into place; otherwise, copy files over (idempotent)
            try:
                # this will fail if base_dir already has content — then we merge below
                tmpdir.replace(base_dir)
            except Exception:
                for root, dirs, files in os.walk(tmpdir):
                    rel = os.path.relpath(root, tmpdir)
                    dest_root = base_dir / (rel if rel != "." else "")
                    dest_root.mkdir(parents=True, exist_ok=True)
                    for d in dirs:
                        (dest_root / d).mkdir(exist_ok=True)
                    for f in files:
                        src = pathlib.Path(root) / f
                        dst = dest_root / f
                        if not dst.exists():
                            shutil.copy2(src, dst)

        ready.write_text("ok\n", encoding="utf-8")
        print("[✓] Base ready.")
    finally:
        try: lock.unlink()
        except Exception: pass

def _hardlink_or_copy(src: pathlib.Path, dst: pathlib.Path):
    try:
        os.link(src, dst)
    except Exception:
        shutil.copy2(src, dst)

def clone_from_base(base_dir: pathlib.Path, dest_dir: pathlib.Path):
    if dest_dir.exists():
        shutil.rmtree(dest_dir, ignore_errors=True)
    dest_dir.mkdir(parents=True, exist_ok=True)

    print(f"[*] Cloning base -> dest\n    base: {base_dir}\n    dest: {dest_dir}")
    # fast path: replicate tree with hardlinks where possible
    for root, dirs, files in os.walk(base_dir):
        rel = os.path.relpath(root, base_dir)
        target_root = dest_dir / (rel if rel != "." else "")
        target_root.mkdir(parents=True, exist_ok=True)
        for d in dirs:
            (target_root / d).mkdir(exist_ok=True)
        for fn in files:
            src = pathlib.Path(root) / fn
            dst = target_root / fn
            _hardlink_or_copy(src, dst)
    return "hardlink/copy"

def clear_worlds(dest_dir: pathlib.Path):
    for world in WORLD_DIRS:
        p = dest_dir / world
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)

def main():
    ap = argparse.ArgumentParser(description="Spawn per-user Paper server quickly from a cached base.")
    ap.add_argument("uuid", help="player UUID or 'warm'")
    ap.add_argument("port", type=int, help="server port")
    ap.add_argument("--rcon-port", type=int, default=None)
    ap.add_argument("--rcon-pass", type=str, default=None)
    ap.add_argument("--template", default="voxelearth.zip", help="server template zip (first-run only)")
    ap.add_argument("--base-dir", default="templates/voxelearth-base", help="cached base extraction dir")
    ap.add_argument("--jar", default="paper.jar")
    ap.add_argument("--java", default="java")
    ap.add_argument("--xms", default=None)
    ap.add_argument("--xmx", default=None)
    ap.add_argument("--empty-world", action="store_true", help="start with no world folders; Paper generates fresh")
    args = ap.parse_args()

    # Resolve everything to absolute paths
    template = (CWD / args.template).resolve()
    base_dir = (CWD / args.base_dir).resolve()
    servers_root = (CWD / "servers").resolve()

    uuid = args.uuid
    port = int(args.port)
    server_name = uuid[:8] if uuid != "warm" else f"warm-{os.getpid():x}"
    folder = (servers_root / f"voxelearth-{server_name}").resolve()

    print(f"[=] CWD     : {CWD}")
    print(f"[=] Template: {template}")
    print(f"[=] Base dir: {base_dir}")
    print(f"[=] Servers : {servers_root}")
    print(f"[=] Folder  : {folder}")

    servers_root.mkdir(parents=True, exist_ok=True)
    ensure_base_from_zip(template, base_dir)
    mode = clone_from_base(base_dir, folder)

    if args.empty_world:
        clear_worlds(folder)

    prop_path = folder / "server.properties"
    props = read_props(prop_path)
    setprop(props, "server-port", str(port))
    if args.rcon_port is not None and args.rcon_pass:
        setprop(props, "enable-rcon", "true")
        setprop(props, "rcon.port", str(args.rcon_port))
        setprop(props, "rcon.password", args.rcon_pass)
    setprop(props, "view-distance", "32")
    setprop(props, "simulation-distance", "6")
    write_props(prop_path, props)
    print(f"[✓] Wrote {prop_path}")

    eula_path = folder / "eula.txt"
    if not eula_path.exists():
        eula_path.write_text("eula=true\n", encoding="utf-8")
        print(f"[✓] Accepted EULA at {eula_path}")

    cuda_path = folder / "cuda_voxelizer"
    if cuda_path.exists() and os.name != "nt":
        try:
            subprocess.run(["chmod", "777", str(cuda_path)], check=False)
        except Exception:
            pass

    # sanity checks before boot
    jar_path = folder / args.jar
    if not jar_path.exists():
        print(f"ERROR: {jar_path} is missing. Does your template include {args.jar}?", file=sys.stderr)
        sys.exit(3)

    # write a per-server log so you can inspect boot
    log_path = folder / "server.log"

    java_cmd = [args.java]
    if args.xms: java_cmd.append(f"-Xms{args.xms}")
    if args.xmx: java_cmd.append(f"-Xmx{args.xmx}")
    java_cmd += [
        "-XX:+UseG1GC","-XX:+ParallelRefProcEnabled","-XX:MaxGCPauseMillis=100",
        "-XX:+UnlockExperimentalVMOptions","-XX:G1NewSizePercent=20","-XX:G1MaxNewSizePercent=30",
        "-XX:G1HeapRegionSize=4M","-XX:G1ReservePercent=15","-XX:InitiatingHeapOccupancyPercent=20",
        "-XX:G1MixedGCLiveThresholdPercent=85","-XX:MaxTenuringThreshold=1","-XX:+DisableExplicitGC",
        "-XX:+AlwaysPreTouch","-XX:+PerfDisableSharedMem","-Daikars.new.flags=true",
        "-jar", args.jar, "--nogui",
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
