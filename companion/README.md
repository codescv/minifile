# MiniFile PC Companion

Pure-Python 3 desktop & command-line companion for **MiniFile** (zero third-party pip dependencies; runs on Python 3.10+ including Python 3.13).

## Quick Start

```bash
cd companion

# Auto-discover your phone running MiniFile Wireless Transfer on Wi-Fi
python3 -m minifile_companion scan --save

# Launch the sleek two-pane Web Desktop Companion (localhost only)
python3 -m minifile_companion gui

# Or use the standalone shim directly from anywhere
./minifile.py ls / -l
```

## CLI Commands

Global options (usable before or after the subcommand):
`-H/--host`, `-P/--port` (default `2121`), `-u/--user`, `-p/--password`, `--timeout`, `--json`, `-v/--verbose`, `--no-update-hint`.

| Command | Example | Description |
|---------|---------|-------------|
| `scan` | `python3 -m minifile_companion scan --save` | Concurrent LAN subnet discovery for MiniFile FTP banners |
| `ls` | `python3 -m minifile_companion ls /Download -la` | List remote directory (`-l` long, `-a` hidden, `-R` recursive) |
| `tree` | `python3 -m minifile_companion tree / -L 2` | Render ASCII directory tree |
| `get` | `python3 -m minifile_companion get /DCIM ./DCIM -r --resume` | Download file or directory with resume & conflict control |
| `put` | `python3 -m minifile_companion put ./notes.md /Documents/` | Upload file or directory recursively |
| `sync` | `python3 -m minifile_companion sync ./Vault /Obsidian --both` | One-way (`--to-phone`, `--to-pc`, `--delete`) or `--both` sync |
| `cat` | `python3 -m minifile_companion cat /Documents/notes.md -n` | Stream remote text file to stdout |
| `edit` | `python3 -m minifile_companion edit /Documents/app.ini --backup` | Edit remote file in `$EDITOR` and upload changes |
| `mkdir` | `python3 -m minifile_companion mkdir /Projects/Demo -p` | Create remote folder(s) |
| `rm` | `python3 -m minifile_companion rm /tmp/old -rf` | Delete remote files or folders |
| `mv` | `python3 -m minifile_companion mv /a.txt /b.txt` | Rename/move via `RNFR`/`RNTO` (`--copy-fallback` across backends) |
| `nomedia` | `python3 -m minifile_companion nomedia /Pictures/Work --on` | Toggle, enable, disable, or query `.nomedia` |
| `shell` | `python3 -m minifile_companion shell` | Interactive FTP REPL (`cd`, `ls`, `lcd`, `get`, `put`, `cat`, …) |
| `gui` / `web` | `python3 -m minifile_companion gui --no-browser` | Localhost dual-pane Web Desktop Companion (or `--tk` when Tkinter is installed) |
| `update` | `python3 -m minifile_companion update --check` | Check/stage updates from `https://github.com/codescv/minifile/releases` |

Exit codes: `0` success, `1` generic error, `2` usage error, `3` connection/auth/network failure, `4` partial failure, `10` update available (`update --check`).

## Running Automated Tests

From the repository root:

```bash
python3 -m unittest discover -s companion/tests -v
```

To run the companion test suite against an external FTP server (e.g., the Kotlin `FtpServerEngine` JVM harness):

```bash
MINIFILE_TEST_SERVER=127.0.0.1:21210 MINIFILE_TEST_ROOT=/tmp/ftp_root \
  python3 -m unittest discover -s companion/tests -v
```
