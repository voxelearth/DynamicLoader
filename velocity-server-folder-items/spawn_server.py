import os, sys, zipfile, shutil, subprocess, time

uuid = sys.argv[1]
port = int(sys.argv[2])
folder = f"servers/voxelearth-{uuid[:8]}"

# Clean old folder if exists
if os.path.exists(folder):
    shutil.rmtree(folder)

# Unzip template
with zipfile.ZipFile("voxelearth.zip", "r") as zip_ref:
    zip_ref.extractall(folder)

# Modify server.properties for new port
prop_path = os.path.join(folder, "server.properties")
with open(prop_path, "r") as f:
    lines = f.readlines()
with open(prop_path, "w") as f:
    for line in lines:
        if line.startswith("server-port="):
            f.write(f"server-port={port}\n")
        else:
            f.write(line)

cuda_path = os.path.join(folder, "cuda_voxelizer")
if os.path.exists(cuda_path):
    subprocess.run(["chmod", "777", cuda_path])
else:
    print(f"Warning: cuda_voxelizer not found in {folder}")

# Start Paper server
subprocess.Popen(
    ["java", "-jar", "paper.jar", "--nogui"],
    cwd=folder,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.STDOUT
)

# Give it time to boot
time.sleep(10)
print(f"Started {folder} on port {port}")
