"""从仓库根运行：python scripts/verify.py。首次请先安装 README 中的依赖。"""
from pathlib import Path
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
os.environ["PYTHONUTF8"] = "1"


def run(command, cwd):
    print("RUN:", " ".join(command), flush=True)
    result = subprocess.run(command, cwd=cwd)
    if result.returncode:
        raise SystemExit(result.returncode)


run([sys.executable, "-m", "pytest", "tests", "-q"], ROOT / "fastapi-service")
run([shutil.which("npm.cmd" if os.name == "nt" else "npm") or "npm", "run", "build"], ROOT / "web-app")
run([shutil.which("mvn.cmd" if os.name == "nt" else "mvn") or "mvn", "-B", "test", "package"], ROOT / "java-app")
run([sys.executable, "scripts/e2e.py"], ROOT)
print("VERIFIED: unit/integration tests, web build, two-service HTTP and restart persistence")
