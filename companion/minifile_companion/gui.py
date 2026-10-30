"""Desktop GUI launcher: native Tkinter two-pane app when available, else Web Desktop Companion."""

from __future__ import annotations

import os
import posixpath
import shutil
import sys
from pathlib import Path
from typing import Any

from .client import MiniFileClient, MiniFileError, normalize_remote_path
from .config import ConfigStore
from .scanner import discover
from .webui import run_webui


def is_display_available() -> bool:
    """Return True if a graphical desktop display is likely available on this OS."""
    if sys.platform in ("win32", "darwin"):
        return True
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def _run_tkinter_gui(
    tk_mod: Any,
    ttk_mod: Any,
    messagebox_mod: Any,
    simpledialog_mod: Any,
    initial_ftp: dict[str, Any] | None = None,
) -> int:
    """Run a clean two-pane native Tkinter desktop companion."""
    store = ConfigStore()
    cfg = store.load()
    host_init = (initial_ftp or {}).get("host") or cfg.host or "192.168.1.1"
    port_init = int((initial_ftp or {}).get("port") or cfg.port or 2121)
    user_init = (initial_ftp or {}).get("user") or cfg.user or "anonymous"
    pass_init = (initial_ftp or {}).get("password") or cfg.password or "minifile@"

    root = tk_mod.Tk()
    root.title("MiniFile Desktop Companion")
    root.geometry("1040x640")

    client_holder: dict[str, MiniFileClient | None] = {"client": None}
    state: dict[str, Any] = {
        "local_dir": str(Path.home()),
        "phone_dir": "/",
        "show_hidden": tk_mod.BooleanVar(master=root, value=False),
        "host": tk_mod.StringVar(master=root, value=host_init),
        "port": tk_mod.StringVar(master=root, value=str(port_init)),
        "user": tk_mod.StringVar(master=root, value=user_init),
        "password": tk_mod.StringVar(master=root, value=pass_init),
        "status": tk_mod.StringVar(master=root, value="Disconnected"),
        "local_entries": [],
        "phone_entries": [],
    }

    top = ttk_mod.Frame(root, padding=8)
    top.pack(fill=tk_mod.X)
    ttk_mod.Label(top, text="Host:").pack(side=tk_mod.LEFT)
    ttk_mod.Entry(top, textvariable=state["host"], width=16).pack(side=tk_mod.LEFT, padx=4)
    ttk_mod.Label(top, text="Port:").pack(side=tk_mod.LEFT)
    ttk_mod.Entry(top, textvariable=state["port"], width=6).pack(side=tk_mod.LEFT, padx=4)
    ttk_mod.Label(top, text="User:").pack(side=tk_mod.LEFT)
    ttk_mod.Entry(top, textvariable=state["user"], width=11).pack(side=tk_mod.LEFT, padx=4)
    ttk_mod.Label(top, text="Pass:").pack(side=tk_mod.LEFT)
    ttk_mod.Entry(top, textvariable=state["password"], show="*", width=11).pack(
        side=tk_mod.LEFT, padx=4
    )

    panes = ttk_mod.PanedWindow(root, orient=tk_mod.HORIZONTAL)
    panes.pack(fill=tk_mod.BOTH, expand=True, padx=8, pady=4)

    left_frame = ttk_mod.Labelframe(panes, text="This PC", padding=6)
    right_frame = ttk_mod.Labelframe(panes, text="Android Phone", padding=6)
    panes.add(left_frame, weight=1)
    panes.add(right_frame, weight=1)

    local_lbl = ttk_mod.Label(left_frame, text=state["local_dir"])
    local_lbl.pack(fill=tk_mod.X)
    local_box = tk_mod.Listbox(left_frame, selectmode=tk_mod.EXTENDED)
    local_box.pack(fill=tk_mod.BOTH, expand=True, pady=4)

    phone_lbl = ttk_mod.Label(right_frame, text="/")
    phone_lbl.pack(fill=tk_mod.X)
    phone_box = tk_mod.Listbox(right_frame, selectmode=tk_mod.EXTENDED)
    phone_box.pack(fill=tk_mod.BOTH, expand=True, pady=4)

    def refresh_local() -> None:
        p = Path(state["local_dir"])
        local_lbl.config(text=str(p))
        local_box.delete(0, tk_mod.END)
        show_h = bool(state["show_hidden"].get())
        items = []
        try:
            for child in sorted(p.iterdir(), key=lambda c: (not c.is_dir(), c.name.lower())):
                if not show_h and child.name.startswith("."):
                    continue
                items.append(child)
        except OSError as exc:
            messagebox_mod.showerror("Local Error", str(exc))
        state["local_entries"] = items
        for c in items:
            prefix = "[DIR] " if c.is_dir() else "      "
            local_box.insert(tk_mod.END, f"{prefix}{c.name}")

    def refresh_phone() -> None:
        cli = client_holder["client"]
        phone_box.delete(0, tk_mod.END)
        if cli is None or not cli.connected:
            state["phone_entries"] = []
            phone_lbl.config(text="Not connected")
            return
        rdir = state["phone_dir"]
        phone_lbl.config(text=rdir)
        try:
            entries = cli.list(rdir, hidden=bool(state["show_hidden"].get()))
            state["phone_entries"] = entries
            for e in entries:
                prefix = "[DIR] " if e.is_dir else "      "
                nm = " [.nomedia]" if e.has_nomedia else ""
                phone_box.insert(tk_mod.END, f"{prefix}{e.name}{nm}")
        except MiniFileError as exc:
            messagebox_mod.showerror("Phone FTP Error", str(exc))

    def do_connect() -> None:
        try:
            if client_holder["client"] is not None:
                client_holder["client"].close()
            cli = MiniFileClient(
                host=state["host"].get().strip(),
                port=int(state["port"].get().strip() or 2121),
                user=state["user"].get().strip() or "anonymous",
                password=state["password"].get(),
            )
            cli.connect()
            client_holder["client"] = cli
            store.update_connection(cli.host, cli.port, cli.user, cli.password)
            state["status"].set(f"Connected to {cli.host}:{cli.port}")
            state["phone_dir"] = "/"
            refresh_phone()
        except Exception as exc:
            state["status"].set("Connection failed")
            messagebox_mod.showerror("Connect Failed", str(exc))

    def do_scan() -> None:
        found = discover(port=int(state["port"].get().strip() or 2121))
        if not found:
            messagebox_mod.showinfo("LAN Scan", "No MiniFile FTP servers discovered.")
            return
        state["host"].set(found[0].host)
        state["port"].set(str(found[0].port))
        do_connect()

    def do_upload() -> None:
        cli = client_holder["client"]
        if cli is None:
            return
        for idx in local_box.curselection():
            src = state["local_entries"][idx]
            dst = normalize_remote_path(posixpath.join(state["phone_dir"], src.name))
            cli.upload(src, dst, overwrite="overwrite")
        refresh_phone()

    def do_download() -> None:
        cli = client_holder["client"]
        if cli is None:
            return
        for idx in phone_box.curselection():
            ent = state["phone_entries"][idx]
            dst = Path(state["local_dir"]) / ent.name
            cli.download(ent.path, dst, overwrite="overwrite")
        refresh_local()

    def do_toggle_nomedia() -> None:
        cli = client_holder["client"]
        if cli is None:
            return
        cur = cli.nomedia(state["phone_dir"], None)
        new_st = cli.nomedia(state["phone_dir"], not cur)
        state["status"].set(
            f"{state['phone_dir']}: .nomedia {'enabled' if new_st else 'removed'}"
        )
        refresh_phone()

    ttk_mod.Button(top, text="Connect", command=do_connect).pack(side=tk_mod.LEFT, padx=3)
    ttk_mod.Button(top, text="Scan LAN", command=do_scan).pack(side=tk_mod.LEFT, padx=3)
    ttk_mod.Checkbutton(
        top,
        text="Show Hidden",
        variable=state["show_hidden"],
        command=lambda: (refresh_local(), refresh_phone()),
    ).pack(side=tk_mod.LEFT, padx=6)
    ttk_mod.Label(top, textvariable=state["status"]).pack(side=tk_mod.RIGHT)

    l_btns = ttk_mod.Frame(left_frame)
    l_btns.pack(fill=tk_mod.X)
    ttk_mod.Button(
        l_btns,
        text="↑ Up",
        command=lambda: (
            state.update(local_dir=str(Path(state["local_dir"]).parent)),
            refresh_local(),
        ),
    ).pack(side=tk_mod.LEFT)
    ttk_mod.Button(l_btns, text="Upload ⟶", command=do_upload).pack(side=tk_mod.RIGHT)

    r_btns = ttk_mod.Frame(right_frame)
    r_btns.pack(fill=tk_mod.X)
    ttk_mod.Button(
        r_btns,
        text="↑ Up",
        command=lambda: (
            state.update(phone_dir=posixpath.dirname(state["phone_dir"]) or "/"),
            refresh_phone(),
        ),
    ).pack(side=tk_mod.LEFT)
    ttk_mod.Button(r_btns, text="Toggle .nomedia", command=do_toggle_nomedia).pack(
        side=tk_mod.LEFT, padx=4
    )
    ttk_mod.Button(r_btns, text="⟵ Download", command=do_download).pack(side=tk_mod.RIGHT)

    local_box.bind(
        "<Double-Button-1>",
        lambda _e: (
            state.update(
                local_dir=str(state["local_entries"][local_box.curselection()[0]])
            ),
            refresh_local(),
        )
        if local_box.curselection()
        and state["local_entries"][local_box.curselection()[0]].is_dir()
        else None,
    )
    phone_box.bind(
        "<Double-Button-1>",
        lambda _e: (
            state.update(
                phone_dir=state["phone_entries"][phone_box.curselection()[0]].path
            ),
            refresh_phone(),
        )
        if phone_box.curselection()
        and state["phone_entries"][phone_box.curselection()[0]].is_dir
        else None,
    )

    refresh_local()
    if initial_ftp and initial_ftp.get("host"):
        do_connect()

    try:
        root.mainloop()
    finally:
        if client_holder["client"] is not None:
            client_holder["client"].close()
    return 0


def run_gui(
    port: int = 8765,
    open_browser: bool = True,
    jail: str | None = None,
    prefer_tk: bool = False,
    force_web: bool = False,
    initial_ftp: dict[str, Any] | None = None,
    verbose: bool = False,
) -> int:
    """Launch Desktop Companion: Web UI by default or graceful fallback when Tkinter/display is absent."""
    if not force_web and prefer_tk:
        if not is_display_available():
            print(
                "No graphical display ($DISPLAY) detected; falling back to Web Desktop Companion.",
                file=sys.stderr,
            )
        else:
            try:
                import tkinter as tk
                from tkinter import messagebox, simpledialog, ttk

                return _run_tkinter_gui(
                    tk, ttk, messagebox, simpledialog, initial_ftp=initial_ftp
                )
            except Exception as exc:
                print(
                    f"Tkinter unavailable ({exc}); falling back to Web Desktop Companion.",
                    file=sys.stderr,
                )

    if not force_web and not prefer_tk:
        # Check tkinter availability so headless/no-tkinter notice is clear per ARCHITECTURE §6.3
        try:
            import tkinter as _tk  # noqa: F401

            if not is_display_available():
                print(
                    "Headless environment detected; starting MiniFile Web Desktop Companion.",
                    file=sys.stderr,
                )
        except ImportError:
            print(
                "Tkinter is not installed on this Python build; starting MiniFile Web Desktop Companion.",
                file=sys.stderr,
            )

    return run_webui(
        port=port,
        open_browser=open_browser,
        jail=jail,
        initial_ftp=initial_ftp,
        verbose=verbose,
    )
