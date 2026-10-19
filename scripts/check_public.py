"""扫描待发布的Git跟踪文件；不读取用户凭据或其他项目。"""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
files = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
patterns = [re.compile(r"gh[pousr]_[A-Za-z0-9]{25,}"), re.compile(r"sk-[A-Za-z0-9_-]{24,}"),
            re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            re.compile(r"[A-Z]:[\\/](?:Users|codex-projects|java)", re.I)]
issues = []
for name in filter(None, files):
    path = ROOT / name
    if path.name.startswith(".env") and path.name != ".env.example":
        issues.append(name + ": environment file")
    if path.suffix.lower() in {".db", ".log", ".jar", ".zip", ".xlsx"}:
        issues.append(name + ": runtime/business artifact")
    if path.suffix.lower() == ".png":
        continue
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeError:
        issues.append(name + ": unexpected binary file")
        continue
    if any(p.search(content) for p in patterns):
        issues.append(name + ": credential or local private path pattern")
if issues:
    raise SystemExit("Public preflight failed:\n" + "\n".join(issues))
print(f"Public preflight passed: {len([f for f in files if f])} tracked files; no matched credential/private-path patterns.")
