(() => {
  "use strict";

  // Extract token from injected meta tag or initial URL query, then strip URL query
  const metaEl = document.querySelector('meta[name="mf-token"]');
  const urlParams = new URLSearchParams(window.location.search);
  const TOKEN = (metaEl && metaEl.content) || urlParams.get("t") || "";
  if (urlParams.has("t")) {
    urlParams.delete("t");
    const cleanSearch = urlParams.toString();
    const cleanUrl = window.location.pathname + (cleanSearch ? `?${cleanSearch}` : "");
    window.history.replaceState({}, document.title, cleanUrl);
  }

  const TEXT_EXTS = new Set([
    "txt", "md", "json", "xml", "yml", "yaml", "log", "csv", "ini", "conf",
    "cfg", "properties", "env", "py", "sh", "bat", "js", "ts", "kt", "java",
    "c", "cpp", "h", "html", "css", "gradle", "toml", "nomedia", "gitignore"
  ]);

  const state = {
    connected: false,
    host: "",
    port: 2121,
    user: "anonymous",
    activePane: "local",
    favorites: [],
    local: {
      path: "",
      parent: null,
      roots: [],
      entries: [],
      selected: new Set(),
      lastClickedIndex: -1,
      hidden: false,
      filter: "",
      sortBy: "name",
      sortAsc: true,
    },
    phone: {
      path: "/",
      parent: null,
      hasNomedia: false,
      entries: [],
      selected: new Set(),
      lastClickedIndex: -1,
      hidden: false,
      filter: "",
      sortBy: "name",
      sortAsc: true,
    },
    editor: {
      open: false,
      pane: "phone",
      path: "",
      encoding: "utf-8",
      dirty: false,
    },
    transferTimer: null,
  };

  const $ = (id) => document.getElementById(id);

  let toastTimer = null;
  function showToast(msg, ms = 3200) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add("hidden"), ms);
  }

  async function api(path, options = {}) {
    const headers = Object.assign({ "X-MF-Token": TOKEN }, options.headers || {});
    if (options.json !== undefined) {
      headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(options.json);
    }
    const resp = await fetch(path, {
      method: options.method || "GET",
      headers,
      body: options.body,
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    return data;
  }

  function formatSize(bytes, isDir) {
    if (isDir) return "—";
    if (bytes == null || bytes < 0) return "—";
    if (bytes < 1024) return `${bytes} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let val = bytes / 1024;
    let idx = 0;
    while (val >= 1024 && idx < units.length - 1) {
      val /= 1024;
      idx++;
    }
    return `${val.toFixed(val >= 10 ? 0 : 1)} ${units[idx]}`;
  }

  function formatMtime(epochSec) {
    if (!epochSec) return "—";
    const d = new Date(epochSec * 1000);
    if (Number.isNaN(d.getTime())) return "—";
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function iconFor(entry) {
    if (entry.is_dir) return "📁";
    const ext = (entry.name.split(".").pop() || "").toLowerCase();
    if (["jpg", "jpeg", "png", "gif", "webp", "svg", "bmp"].includes(ext)) return "🖼";
    if (["mp4", "mkv", "mov", "webm", "avi"].includes(ext)) return "🎬";
    if (["mp3", "flac", "wav", "ogg", "m4a"].includes(ext)) return "🎵";
    if (["zip", "tar", "gz", "7z", "rar"].includes(ext)) return "🗜";
    if (ext === "apk") return "🤖";
    if (TEXT_EXTS.has(ext) || entry.name.startsWith(".")) return "📝";
    return "📄";
  }

  function isLikelyTextFile(entry) {
    if (entry.is_dir) return false;
    const lower = entry.name.toLowerCase();
    if (lower === ".nomedia" || lower === ".gitignore") return true;
    const parts = lower.split(".");
    if (parts.length > 1 && TEXT_EXTS.has(parts.pop())) return true;
    return entry.size >= 0 && entry.size <= 256 * 1024;
  }

  function getFilteredSorted(paneKey) {
    const p = state[paneKey];
    const q = p.filter.trim().toLowerCase();
    let list = p.entries.filter((e) => !q || e.name.toLowerCase().includes(q));
    const dirMul = p.sortAsc ? 1 : -1;
    list.sort((a, b) => {
      if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
      if (p.sortBy === "size") {
        return ((a.size || 0) - (b.size || 0)) * dirMul;
      }
      if (p.sortBy === "mtime") {
        return ((a.mtime || 0) - (b.mtime || 0)) * dirMul;
      }
      return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }) * dirMul;
    });
    return list;
  }

  function renderBreadcrumbs(paneKey) {
    const nav = $(`${paneKey}-crumbs`);
    nav.innerHTML = "";
    const fullPath = state[paneKey].path || "/";

    if (paneKey === "phone") {
      const segs = fullPath.split("/").filter(Boolean);
      const addCrumb = (label, target) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "crumb";
        btn.textContent = label;
        btn.addEventListener("click", () => loadPhone(target));
        nav.appendChild(btn);
      };
      addCrumb("Phone (/)", "/");
      let acc = "";
      for (const s of segs) {
        const sep = document.createElement("span");
        sep.className = "crumb-sep";
        sep.textContent = "›";
        nav.appendChild(sep);
        acc += `/${s}`;
        addCrumb(s, acc);
      }
    } else {
      const isWin = fullPath.includes("\\");
      const sepChar = isWin ? "\\" : "/";
      const parts = fullPath.split(sepChar).filter(Boolean);
      let acc = isWin ? "" : "/";
      if (!isWin) {
        const rootBtn = document.createElement("button");
        rootBtn.type = "button";
        rootBtn.className = "crumb";
        rootBtn.textContent = "/";
        rootBtn.addEventListener("click", () => loadLocal("/"));
        nav.appendChild(rootBtn);
      }
      parts.forEach((part, idx) => {
        if (idx > 0 || !isWin) {
          const sep = document.createElement("span");
          sep.className = "crumb-sep";
          sep.textContent = "›";
          nav.appendChild(sep);
        }
        acc = isWin ? (idx === 0 ? `${part}\\` : `${acc.replace(/\\$/, "")}\\${part}`) : `${acc === "/" ? "" : acc}/${part}`;
        const target = acc;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "crumb";
        btn.textContent = part;
        btn.addEventListener("click", () => loadLocal(target));
        nav.appendChild(btn);
      });
    }
    nav.scrollLeft = nav.scrollWidth;
  }

  function updateSelectionButtons() {
    $("btn-transfer-up").disabled = !state.connected || state.local.selected.size === 0;
    $("btn-transfer-down").disabled = !state.connected || state.phone.selected.size === 0;
  }

  function renderPane(paneKey) {
    const p = state[paneKey];
    const container = $(`${paneKey}-list`);
    renderBreadcrumbs(paneKey);

    if (paneKey === "phone" && !state.connected) {
      container.innerHTML = `
        <div class="empty-state">
          <p>Connect to your phone's MiniFile FTP server to browse files wirelessly.</p>
          <div class="empty-actions">
            <button id="btn-empty-connect-dyn" class="btn btn-primary" type="button">Connect to Phone</button>
            <button id="btn-empty-scan-dyn" class="btn btn-tonal" type="button">Scan Wi-Fi</button>
          </div>
        </div>`;
      $("btn-empty-connect-dyn").addEventListener("click", openConnectModal);
      $("btn-empty-scan-dyn").addEventListener("click", openScanModal);
      $("phone-footer").textContent = "Not connected";
      updateSelectionButtons();
      return;
    }

    const entries = getFilteredSorted(paneKey);
    container.innerHTML = "";

    if (entries.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = p.filter ? "No matching entries." : "This folder is empty.";
      container.appendChild(empty);
    } else {
      entries.forEach((entry, index) => {
        const row = document.createElement("div");
        row.className = "file-row";
        row.setAttribute("role", "option");
        if (p.selected.has(entry.path)) row.classList.add("selected");
        if (entry.is_hidden) row.classList.add("is-hidden");
        row.draggable = true;

        const nameCell = document.createElement("div");
        nameCell.className = "name-cell";
        const iconSpan = document.createElement("span");
        iconSpan.className = "entry-icon";
        iconSpan.textContent = iconFor(entry);
        const nameSpan = document.createElement("span");
        nameSpan.className = "entry-name";
        nameSpan.textContent = entry.name;
        nameSpan.title = entry.name;
        nameCell.append(iconSpan, nameSpan);

        if (entry.is_dir && entry.has_nomedia) {
          const nm = document.createElement("span");
          nm.className = "nomedia-badge";
          nm.title = "Hidden from gallery (.nomedia)";
          nm.textContent = "🚫 .nomedia";
          nameCell.appendChild(nm);
        }

        const sizeCell = document.createElement("div");
        sizeCell.className = "size-cell";
        sizeCell.textContent = formatSize(entry.size, entry.is_dir);

        const mtimeCell = document.createElement("div");
        mtimeCell.className = "mtime-cell";
        mtimeCell.textContent = formatMtime(entry.mtime);

        const actionsCell = document.createElement("div");
        actionsCell.className = "actions-cell";

        if (paneKey === "phone") {
          const dlLink = document.createElement("a");
          dlLink.className = "btn btn-icon";
          dlLink.href = `/api/download?path=${encodeURIComponent(entry.path)}`;
          dlLink.title = entry.is_dir ? "Download folder as .zip" : "Download file";
          dlLink.textContent = "⬇";
          dlLink.addEventListener("click", (ev) => ev.stopPropagation());
          actionsCell.appendChild(dlLink);
        }

        const menuBtn = document.createElement("button");
        menuBtn.type = "button";
        menuBtn.className = "btn btn-icon";
        menuBtn.textContent = "⋮";
        menuBtn.title = "Actions";
        menuBtn.addEventListener("click", (ev) => {
          ev.stopPropagation();
          if (!p.selected.has(entry.path)) {
            p.selected.clear();
            p.selected.add(entry.path);
            renderPane(paneKey);
          }
          showContextMenu(ev.clientX, ev.clientY, paneKey, entry);
        });
        actionsCell.appendChild(menuBtn);

        row.append(nameCell, sizeCell, mtimeCell, actionsCell);

        row.addEventListener("click", (ev) => {
          state.activePane = paneKey;
          if (ev.shiftKey && p.lastClickedIndex >= 0) {
            const lo = Math.min(p.lastClickedIndex, index);
            const hi = Math.max(p.lastClickedIndex, index);
            if (!ev.ctrlKey && !ev.metaKey) p.selected.clear();
            for (let i = lo; i <= hi; i++) p.selected.add(entries[i].path);
          } else if (ev.ctrlKey || ev.metaKey) {
            if (p.selected.has(entry.path)) p.selected.delete(entry.path);
            else p.selected.add(entry.path);
            p.lastClickedIndex = index;
          } else {
            p.selected.clear();
            p.selected.add(entry.path);
            p.lastClickedIndex = index;
          }
          renderPane(paneKey);
        });

        row.addEventListener("dblclick", () => openEntry(paneKey, entry));

        row.addEventListener("contextmenu", (ev) => {
          ev.preventDefault();
          state.activePane = paneKey;
          if (!p.selected.has(entry.path)) {
            p.selected.clear();
            p.selected.add(entry.path);
            renderPane(paneKey);
          }
          showContextMenu(ev.clientX, ev.clientY, paneKey, entry);
        });

        row.addEventListener("dragstart", (ev) => {
          if (!p.selected.has(entry.path)) {
            p.selected.clear();
            p.selected.add(entry.path);
            renderPane(paneKey);
          }
          const payload = JSON.stringify({
            fromPane: paneKey,
            paths: Array.from(p.selected),
          });
          ev.dataTransfer.setData("application/x-minifile-items", payload);
          ev.dataTransfer.effectAllowed = "copy";
        });

        container.appendChild(row);
      });
    }

    const selCount = p.selected.size;
    $(`${paneKey}-footer`).textContent =
      selCount > 0 ? `${entries.length} items · ${selCount} selected` : `${entries.length} items`;

    if (paneKey === "phone") {
      $("phone-nomedia").setAttribute("aria-pressed", String(Boolean(p.hasNomedia)));
    }
    updateSelectionButtons();
  }

  async function loadLocal(targetPath) {
    try {
      const qPath = targetPath !== undefined ? targetPath : state.local.path;
      const hiddenParam = state.local.hidden ? "1" : "0";
      const data = await api(`/api/local/list?path=${encodeURIComponent(qPath)}&hidden=${hiddenParam}`);
      state.local.path = data.path;
      state.local.parent = data.parent;
      state.local.roots = data.roots || [];
      state.local.entries = data.entries || [];
      state.local.selected.clear();
      state.local.lastClickedIndex = -1;

      const rootSel = $("local-roots");
      rootSel.innerHTML = "";
      state.local.roots.forEach((r) => {
        const opt = document.createElement("option");
        opt.value = r.path;
        opt.textContent = r.name;
        if (state.local.path.startsWith(r.path)) opt.selected = true;
        rootSel.appendChild(opt);
      });
      renderPane("local");
    } catch (err) {
      showToast(`PC list failed: ${err.message}`);
    }
  }

  async function loadPhone(targetPath) {
    if (!state.connected) {
      renderPane("phone");
      return;
    }
    try {
      const qPath = targetPath !== undefined ? targetPath : state.phone.path;
      const hiddenParam = state.phone.hidden ? "1" : "0";
      const data = await api(`/api/phone/list?path=${encodeURIComponent(qPath)}&hidden=${hiddenParam}`);
      state.phone.path = data.path;
      state.phone.parent = data.parent;
      state.phone.hasNomedia = Boolean(data.has_nomedia);
      state.phone.entries = data.entries || [];
      state.phone.selected.clear();
      state.phone.lastClickedIndex = -1;
      renderPane("phone");
    } catch (err) {
      showToast(`Phone list failed: ${err.message}`);
    }
  }

  function applyStatus(status) {
    state.connected = Boolean(status.connected);
    state.host = status.host || "";
    state.port = status.port || 2121;
    state.user = status.user || "anonymous";
    if (status.version) {
      $("app-version").textContent = `v${status.version}`;
    }

    const pill = $("conn-indicator");
    const label = $("conn-label");
    if (state.connected) {
      pill.classList.remove("disconnected");
      pill.classList.add("connected");
      label.textContent = `${state.host}:${state.port}`;
      $("btn-disconnect").classList.remove("hidden");
    } else {
      pill.classList.remove("connected");
      pill.classList.add("disconnected");
      label.textContent = "Disconnected";
      $("btn-disconnect").classList.add("hidden");
    }
    updateSelectionButtons();
  }

  function openEntry(paneKey, entry) {
    if (entry.is_dir) {
      if (paneKey === "local") loadLocal(entry.path);
      else loadPhone(entry.path);
      return;
    }
    if (isLikelyTextFile(entry)) {
      openEditor(paneKey, entry.path, entry.name);
    } else if (paneKey === "phone") {
      window.location.href = `/api/download?path=${encodeURIComponent(entry.path)}`;
    }
  }

  // Context Menu
  function hideMenus() {
    $("context-menu").classList.add("hidden");
    $("fav-menu").classList.add("hidden");
  }

  function showContextMenu(x, y, paneKey, entry) {
    const menu = $("context-menu");
    menu.innerHTML = "";
    const addItem = (label, fn, danger = false) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = `menu-item${danger ? " danger" : ""}`;
      b.textContent = label;
      b.addEventListener("click", () => {
        hideMenus();
        fn();
      });
      menu.appendChild(b);
    };
    const addSep = () => {
      const s = document.createElement("div");
      s.className = "menu-sep";
      menu.appendChild(s);
    };

    if (entry.is_dir) {
      addItem("Open Folder", () => openEntry(paneKey, entry));
    } else {
      addItem("Edit as Text…", () => openEditor(paneKey, entry.path, entry.name));
    }

    if (paneKey === "local" && state.connected) {
      addItem("⟶ Upload to Phone", () => queueTransfer("up", Array.from(state.local.selected)));
    }
    if (paneKey === "phone") {
      if (state.connected) {
        addItem("⟵ Download to PC", () => queueTransfer("down", Array.from(state.phone.selected)));
      }
      addItem(entry.is_dir ? "⬇ Download Folder (.zip)" : "⬇ Browser Download", () => {
        window.location.href = `/api/download?path=${encodeURIComponent(entry.path)}`;
      });
      if (entry.is_dir) {
        addSep();
        addItem(entry.has_nomedia ? "Show in Gallery (Remove .nomedia)" : "🚫 Hide from Gallery (.nomedia)", async () => {
          try {
            const res = await api("/api/phone/nomedia", {
              method: "POST",
              json: { path: entry.path, state: !entry.has_nomedia },
            });
            showToast(`${entry.name}: .nomedia ${res.state ? "enabled" : "removed"}`);
            loadPhone();
          } catch (err) {
            showToast(err.message);
          }
        });
        addItem("★ Add to Favorites", () => addFavorite(entry.name, entry.path));
      }
    }

    addSep();
    addItem("Rename (F2)", () => promptRename(paneKey, entry));
    addItem("Delete (Del)", () => promptDelete(paneKey), true);

    menu.classList.remove("hidden");
    const rect = menu.getBoundingClientRect();
    const left = Math.min(x, window.innerWidth - rect.width - 10);
    const top = Math.min(y, window.innerHeight - rect.height - 10);
    menu.style.left = `${Math.max(8, left)}px`;
    menu.style.top = `${Math.max(8, top)}px`;
  }

  // Operations: mkdir, new file, rename, delete
  async function promptMkdir(paneKey) {
    if (paneKey === "phone" && !state.connected) return;
    const name = window.prompt("New folder name:", "New folder");
    if (!name || !name.trim()) return;
    try {
      const endpoint = paneKey === "local" ? "/api/local/mkdir" : "/api/phone/mkdir";
      await api(endpoint, {
        method: "POST",
        json: { path: state[paneKey].path, name: name.trim() },
      });
      if (paneKey === "local") await loadLocal();
      else await loadPhone();
    } catch (err) {
      showToast(`Create folder failed: ${err.message}`);
    }
  }

  async function promptNewFile(paneKey) {
    if (paneKey === "phone" && !state.connected) return;
    const name = window.prompt("New text file name:", "untitled.txt");
    if (!name || !name.trim()) return;
    const clean = name.trim();
    const base = state[paneKey].path;
    const sep = paneKey === "local" && base.includes("\\") ? "\\" : "/";
    const full = base.endsWith(sep) ? `${base}${clean}` : `${base}${sep}${clean}`;
    try {
      const endpoint = paneKey === "local" ? "/api/local/text" : "/api/phone/text";
      await api(endpoint, {
        method: "PUT",
        json: { path: full, text: "", encoding: "utf-8" },
      });
      if (paneKey === "local") await loadLocal();
      else await loadPhone();
      openEditor(paneKey, full, clean);
    } catch (err) {
      showToast(`Create file failed: ${err.message}`);
    }
  }

  async function promptRename(paneKey, entry) {
    if (!entry) {
      const sel = Array.from(state[paneKey].selected);
      if (sel.length !== 1) return;
      entry = state[paneKey].entries.find((e) => e.path === sel[0]);
      if (!entry) return;
    }
    const newName = window.prompt("Rename to:", entry.name);
    if (!newName || !newName.trim() || newName.trim() === entry.name) return;
    try {
      const endpoint = paneKey === "local" ? "/api/local/rename" : "/api/phone/rename";
      await api(endpoint, {
        method: "POST",
        json: { path: entry.path, new_name: newName.trim() },
      });
      if (paneKey === "local") await loadLocal();
      else await loadPhone();
    } catch (err) {
      showToast(`Rename failed: ${err.message}`);
    }
  }

  async function promptDelete(paneKey) {
    const paths = Array.from(state[paneKey].selected);
    if (paths.length === 0) return;
    const ok = window.confirm(`Delete ${paths.length} item(s)? This cannot be undone.`);
    if (!ok) return;
    try {
      const endpoint = paneKey === "local" ? "/api/local/delete" : "/api/phone/delete";
      const res = await api(endpoint, {
        method: "POST",
        json: { paths, recursive: true },
      });
      if (res.failed && res.failed.length > 0) {
        showToast(`Deleted ${res.deleted}, failed ${res.failed.length}: ${res.failed[0].error}`);
      } else {
        showToast(`Deleted ${res.deleted} item(s)`);
      }
      if (paneKey === "local") await loadLocal();
      else await loadPhone();
    } catch (err) {
      showToast(`Delete failed: ${err.message}`);
    }
  }

  // Transfers Queue (PC <-> Phone)
  async function queueTransfer(direction, srcPaths) {
    if (!state.connected || srcPaths.length === 0) return;
    const items = srcPaths.map((src) => {
      const baseName = src.replace(/\\/g, "/").split("/").filter(Boolean).pop() || "item";
      if (direction === "up") {
        const dstDir = state.phone.path === "/" ? "" : state.phone.path;
        return { src, dst: `${dstDir}/${baseName}` };
      }
      const sep = state.local.path.includes("\\") ? "\\" : "/";
      const dstLocal = state.local.path.endsWith(sep)
        ? `${state.local.path}${baseName}`
        : `${state.local.path}${sep}${baseName}`;
      return { src, dst: dstLocal };
    });

    try {
      await api("/api/transfer", {
        method: "POST",
        json: { direction, items, overwrite: "overwrite" },
      });
      $("transfer-bar").classList.remove("hidden");
      pollTransfers();
    } catch (err) {
      showToast(`Transfer failed to start: ${err.message}`);
    }
  }

  async function pollTransfers() {
    clearTimeout(state.transferTimer);
    try {
      const jobs = await api("/api/transfers");
      renderTransfers(jobs);
      const anyActive = jobs.some((j) => j.state === "queued" || j.state === "running");
      if (anyActive) {
        state.transferTimer = setTimeout(pollTransfers, 450);
      } else if (jobs.length > 0) {
        await Promise.all([loadLocal(), loadPhone()]);
      }
    } catch (_) {
      // Ignore transient poll error
    }
  }

  function renderTransfers(jobs) {
    const wrap = $("transfer-items");
    wrap.innerHTML = "";
    if (!jobs || jobs.length === 0) return;
    $("transfer-bar").classList.remove("hidden");

    const runningCount = jobs.filter((j) => j.state === "running" || j.state === "queued").length;
    $("transfer-summary").textContent =
      runningCount > 0 ? `Transfers (${runningCount} active)` : "Transfers";

    jobs.slice(0, 8).forEach((job) => {
      const row = document.createElement("div");
      row.className = "transfer-row";
      const pct =
        job.total_bytes > 0
          ? Math.min(100, Math.round((job.done_bytes / job.total_bytes) * 100))
          : job.state === "completed"
          ? 100
          : 0;
      const arrow = job.direction === "up" ? "↑" : "↓";
      const label = document.createElement("div");
      label.textContent = `${arrow} ${job.current || job.job_id} (${job.state})`;
      label.style.overflow = "hidden";
      label.style.textOverflow = "ellipsis";
      label.style.whiteSpace = "nowrap";

      const bar = document.createElement("div");
      bar.className = "progress-track";
      const fill = document.createElement("div");
      fill.className = "progress-fill";
      fill.style.width = `${pct}%`;
      bar.appendChild(fill);

      const meta = document.createElement("div");
      if (job.error) {
        meta.textContent = job.error;
        meta.style.color = "var(--danger)";
      } else {
        const spd = job.speed > 0 ? `${formatSize(job.speed, false)}/s` : "";
        meta.textContent = `${pct}% ${spd}`.trim();
      }

      const act = document.createElement("div");
      if (job.state === "running" || job.state === "queued") {
        const cancelBtn = document.createElement("button");
        cancelBtn.type = "button";
        cancelBtn.className = "btn btn-ghost btn-sm";
        cancelBtn.textContent = "Cancel";
        cancelBtn.addEventListener("click", async () => {
          await api(`/api/transfers/${encodeURIComponent(job.job_id)}/cancel`, { method: "POST" });
          pollTransfers();
        });
        act.appendChild(cancelBtn);
      }

      row.append(label, bar, meta, act);
      wrap.appendChild(row);
    });
  }

  // OS Drag & Drop / Multipart Upload onto Phone Pane
  async function collectDroppedFiles(dataTransfer) {
    const out = [];
    const items = Array.from(dataTransfer.items || []);
    const entries = items
      .map((it) => (typeof it.webkitGetAsEntry === "function" ? it.webkitGetAsEntry() : null))
      .filter(Boolean);

    if (entries.length > 0) {
      async function walkEntry(entry, relDir) {
        if (entry.isFile) {
          const file = await new Promise((res, rej) => entry.file(res, rej));
          out.push({ file, relDir });
        } else if (entry.isDirectory) {
          const reader = entry.createReader();
          const nextRel = relDir ? `${relDir}/${entry.name}` : entry.name;
          let batch = [];
          do {
            batch = await new Promise((res, rej) => reader.readEntries(res, rej));
            for (const child of batch) {
              await walkEntry(child, nextRel);
            }
          } while (batch.length > 0);
        }
      }
      for (const e of entries) {
        await walkEntry(e, "");
      }
      return out;
    }

    for (const file of Array.from(dataTransfer.files || [])) {
      out.push({ file, relDir: "" });
    }
    return out;
  }

  async function uploadFilesMultipart(fileItems) {
    if (!state.connected || fileItems.length === 0) return;
    const form = new FormData();
    for (const item of fileItems) {
      if (item.relDir) {
        form.append("relpath", item.relDir);
      }
      form.append("file", item.file, item.file.name);
    }
    showToast(`Uploading ${fileItems.length} file(s)…`);
    try {
      const res = await api(`/api/upload?dst=${encodeURIComponent(state.phone.path)}`, {
        method: "POST",
        body: form,
      });
      showToast(`Uploaded ${(res.stored || []).length} file(s)`);
      await loadPhone();
    } catch (err) {
      showToast(`Upload failed: ${err.message}`);
    }
  }

  // Favorites
  async function loadFavorites() {
    try {
      state.favorites = await api("/api/favorites");
    } catch (_) {
      state.favorites = [];
    }
  }

  async function addFavorite(name, path) {
    const cleanName = window.prompt("Favorite display name:", name || path.split("/").pop() || "/");
    if (!cleanName) return;
    const next = state.favorites.filter((f) => f.path !== path);
    next.push({ name: cleanName.trim(), path });
    state.favorites = await api("/api/favorites", { method: "PUT", json: next });
    showToast(`Added "${cleanName.trim()}" to Favorites`);
  }

  async function removeFavorite(path) {
    const next = state.favorites.filter((f) => f.path !== path);
    state.favorites = await api("/api/favorites", { method: "PUT", json: next });
    renderFavMenu();
  }

  function renderFavMenu() {
    const menu = $("fav-menu");
    menu.innerHTML = "";
    state.favorites.forEach((fav) => {
      const row = document.createElement("div");
      row.style.display = "flex";
      row.style.alignItems = "center";

      const goBtn = document.createElement("button");
      goBtn.type = "button";
      goBtn.className = "menu-item";
      goBtn.textContent = `★ ${fav.name} (${fav.path})`;
      goBtn.addEventListener("click", () => {
        hideMenus();
        if (state.connected) loadPhone(fav.path);
      });

      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "btn btn-icon";
      delBtn.textContent = "✕";
      delBtn.title = "Remove favorite";
      delBtn.addEventListener("click", (ev) => {
        ev.stopPropagation();
        removeFavorite(fav.path);
      });

      row.append(goBtn, delBtn);
      menu.appendChild(row);
    });

    const sep = document.createElement("div");
    sep.className = "menu-sep";
    menu.appendChild(sep);

    const addCur = document.createElement("button");
    addCur.type = "button";
    addCur.className = "menu-item";
    addCur.textContent = "＋ Bookmark Current Phone Folder";
    addCur.disabled = !state.connected;
    addCur.addEventListener("click", () => {
      hideMenus();
      const folderName = state.phone.path.split("/").filter(Boolean).pop() || "Root";
      addFavorite(folderName, state.phone.path);
    });
    menu.appendChild(addCur);
  }

  // Inline Text Editor
  function updateEditorGutter() {
    const ta = $("editor-textarea");
    const lines = ta.value.split("\n").length;
    const nums = [];
    for (let i = 1; i <= lines; i++) nums.push(i);
    $("editor-gutter").textContent = nums.join("\n");

    const pos = ta.selectionStart || 0;
    const upToCursor = ta.value.slice(0, pos).split("\n");
    const ln = upToCursor.length;
    const col = upToCursor[upToCursor.length - 1].length + 1;
    $("editor-status").textContent =
      `${state.editor.encoding.toUpperCase()} · Ln ${ln}, Col ${col} · ${ta.value.length} chars`;
  }

  async function openEditor(paneKey, filePath, displayName, explicitEncoding = "") {
    try {
      const baseEp = paneKey === "local" ? "/api/local/text" : "/api/phone/text";
      const encQuery = explicitEncoding ? `&encoding=${encodeURIComponent(explicitEncoding)}` : "";
      const res = await api(`${baseEp}?path=${encodeURIComponent(filePath)}${encQuery}`);
      state.editor.open = true;
      state.editor.pane = paneKey;
      state.editor.path = filePath;
      state.editor.encoding = res.encoding || "utf-8";
      state.editor.dirty = false;

      $("editor-title").textContent = displayName || filePath.split(/[\\/]/).pop();
      $("editor-encoding").value = state.editor.encoding.toLowerCase();
      $("editor-textarea").value = res.text || "";
      $("editor-dirty").classList.add("hidden");
      $("modal-editor").classList.remove("hidden");
      updateEditorGutter();
      $("editor-textarea").focus();
    } catch (err) {
      showToast(`Cannot open text editor: ${err.message}`);
    }
  }

  async function saveEditor() {
    if (!state.editor.open) return;
    try {
      const ep = state.editor.pane === "local" ? "/api/local/text" : "/api/phone/text";
      const enc = $("editor-encoding").value || "utf-8";
      await api(ep, {
        method: "PUT",
        json: {
          path: state.editor.path,
          text: $("editor-textarea").value,
          encoding: enc,
        },
      });
      state.editor.encoding = enc;
      state.editor.dirty = false;
      $("editor-dirty").classList.add("hidden");
      updateEditorGutter();
      showToast("Saved");
      if (state.editor.pane === "local") loadLocal();
      else loadPhone();
    } catch (err) {
      showToast(`Save failed: ${err.message}`);
    }
  }

  function closeEditor() {
    if (state.editor.dirty && !window.confirm("Discard unsaved changes?")) return;
    state.editor.open = false;
    state.editor.dirty = false;
    $("modal-editor").classList.add("hidden");
  }

  // Connect & Scan Modals
  function openConnectModal() {
    $("inp-host").value = state.host || "";
    $("inp-port").value = state.port || 2121;
    $("inp-user").value = state.user || "anonymous";
    $("modal-connect").classList.remove("hidden");
    $("inp-host").focus();
  }

  function openScanModal() {
    $("modal-scan").classList.remove("hidden");
  }

  async function runLanScan() {
    const resBox = $("scan-results");
    resBox.innerHTML = `<p class="muted">Scanning local network…</p>`;
    const subnet = $("inp-scan-subnet").value.trim();
    const port = $("inp-scan-port").value || "2121";
    try {
      const list = await api(`/api/scan?subnet=${encodeURIComponent(subnet)}&port=${encodeURIComponent(port)}`);
      resBox.innerHTML = "";
      if (!list || list.length === 0) {
        resBox.innerHTML = `<p class="muted">No MiniFile FTP servers found. Make sure Wireless Transfer is started on your phone.</p>`;
        return;
      }
      list.forEach((srv) => {
        const card = document.createElement("div");
        card.className = "scan-item";
        const info = document.createElement("div");
        info.innerHTML = `<strong>${srv.host}:${srv.port}</strong><br><small>${srv.banner} · ${srv.rtt_ms} ms</small>`;
        const pick = document.createElement("button");
        pick.type = "button";
        pick.className = "btn btn-primary btn-sm";
        pick.textContent = "Connect";
        pick.addEventListener("click", async () => {
          $("modal-scan").classList.add("hidden");
          try {
            const st = await api("/api/connect", {
              method: "POST",
              json: { host: srv.host, port: srv.port, user: state.user || "anonymous", password: "minifile@", remember: true },
            });
            applyStatus(st);
            await loadPhone("/");
            showToast(`Connected to ${srv.host}:${srv.port}`);
          } catch (err) {
            $("inp-host").value = srv.host;
            $("inp-port").value = srv.port;
            openConnectModal();
            showToast(`Authentication required: ${err.message}`);
          }
        });
        card.append(info, pick);
        resBox.appendChild(card);
      });
    } catch (err) {
      resBox.innerHTML = `<p class="muted">Scan error: ${err.message}</p>`;
    }
  }

  // Updates Checker & Badge
  async function checkUpdates(force = false) {
    try {
      const data = await api(`/api/update?force=${force ? "1" : "0"}`);
      const badge = $("btn-update-badge");
      if (data.available) {
        badge.textContent = `⬆ ${data.latest} available`;
        badge.classList.remove("hidden");
      } else {
        badge.classList.add("hidden");
      }

      const summary = $("update-summary");
      const notes = $("update-notes");
      const dlLink = $("link-download-update");
      const relLink = $("link-release-page");

      relLink.href = data.html_url || "https://github.com/codescv/minifile/releases";
      if (data.available) {
        const uvHint = data.uv_command
          ? ` — Run \`minifile update --apply\` or \`${data.uv_command}\``
          : "";
        summary.textContent = `Current v${data.current} · Latest ${data.latest} — Update available!${uvHint}`;
        if (data.notes) {
          notes.textContent = data.notes;
          notes.classList.remove("hidden");
        } else {
          notes.classList.add("hidden");
        }
        if (data.asset_url) {
          dlLink.href = data.asset_url;
          dlLink.classList.remove("hidden");
        } else {
          dlLink.classList.add("hidden");
        }
      } else {
        const throttledNote = data.throttled ? " (cached — checked < 60s ago)" : "";
        summary.textContent = `You're up to date (v${data.current})${throttledNote}.`;
        notes.classList.add("hidden");
        dlLink.classList.add("hidden");
      }
    } catch (err) {
      if (force) {
        $("update-summary").textContent = `Update check failed: ${err.message}`;
      }
    }
  }

  // Event Wiring
  function bindEvents() {
    document.addEventListener("click", () => hideMenus());

    document.querySelectorAll(".modal-close").forEach((btn) => {
      btn.addEventListener("click", () => {
        const id = btn.getAttribute("data-close");
        if (id) $(id).classList.add("hidden");
      });
    });

    $("btn-connect-modal").addEventListener("click", openConnectModal);
    $("btn-empty-connect").addEventListener("click", openConnectModal);
    $("btn-scan-modal").addEventListener("click", openScanModal);
    $("btn-empty-scan").addEventListener("click", openScanModal);
    $("btn-run-scan").addEventListener("click", runLanScan);

    $("form-connect").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      try {
        const st = await api("/api/connect", {
          method: "POST",
          json: {
            host: $("inp-host").value.trim(),
            port: Number($("inp-port").value || 2121),
            user: $("inp-user").value.trim() || "anonymous",
            password: $("inp-pass").value,
            remember: $("inp-remember").checked,
          },
        });
        $("modal-connect").classList.add("hidden");
        applyStatus(st);
        await loadPhone("/");
        showToast(`Connected to ${st.host}:${st.port}`);
      } catch (err) {
        showToast(`Connection failed: ${err.message}`);
      }
    });

    $("btn-disconnect").addEventListener("click", async () => {
      const st = await api("/api/disconnect", { method: "POST" });
      applyStatus(st);
      renderPane("phone");
    });

    // Local toolbar
    $("local-up").addEventListener("click", () => {
      if (state.local.parent) loadLocal(state.local.parent);
    });
    $("local-home").addEventListener("click", () => loadLocal(""));
    $("local-refresh").addEventListener("click", () => loadLocal());
    $("local-roots").addEventListener("change", (ev) => loadLocal(ev.target.value));
    $("local-mkdir").addEventListener("click", () => promptMkdir("local"));
    $("local-newfile").addEventListener("click", () => promptNewFile("local"));
    $("local-hidden").addEventListener("click", () => {
      state.local.hidden = !state.local.hidden;
      $("local-hidden").setAttribute("aria-pressed", String(state.local.hidden));
      loadLocal();
    });
    $("local-filter").addEventListener("input", (ev) => {
      state.local.filter = ev.target.value;
      renderPane("local");
    });

    // Phone toolbar
    $("phone-up").addEventListener("click", () => {
      if (state.phone.parent) loadPhone(state.phone.parent);
    });
    $("phone-root").addEventListener("click", () => loadPhone("/"));
    $("phone-refresh").addEventListener("click", () => loadPhone());
    $("phone-mkdir").addEventListener("click", () => promptMkdir("phone"));
    $("phone-newfile").addEventListener("click", () => promptNewFile("phone"));
    $("phone-nomedia").addEventListener("click", async () => {
      if (!state.connected) return;
      try {
        const res = await api("/api/phone/nomedia", {
          method: "POST",
          json: { path: state.phone.path, state: !state.phone.hasNomedia },
        });
        showToast(`.nomedia ${res.state ? "created" : "removed"} in ${state.phone.path}`);
        await loadPhone();
      } catch (err) {
        showToast(err.message);
      }
    });
    $("phone-hidden").addEventListener("click", () => {
      state.phone.hidden = !state.phone.hidden;
      $("phone-hidden").setAttribute("aria-pressed", String(state.phone.hidden));
      loadPhone();
    });
    $("phone-filter").addEventListener("input", (ev) => {
      state.phone.filter = ev.target.value;
      renderPane("phone");
    });

    $("btn-fav-menu").addEventListener("click", (ev) => {
      ev.stopPropagation();
      renderFavMenu();
      $("fav-menu").classList.toggle("hidden");
    });

    $("btn-transfer-up").addEventListener("click", () =>
      queueTransfer("up", Array.from(state.local.selected))
    );
    $("btn-transfer-down").addEventListener("click", () =>
      queueTransfer("down", Array.from(state.phone.selected))
    );

    $("file-upload-input").addEventListener("change", async (ev) => {
      const files = Array.from(ev.target.files || []).map((f) => ({ file: f, relDir: "" }));
      ev.target.value = "";
      await uploadFilesMultipart(files);
    });

    // Sorting headers
    document.querySelectorAll(".sort-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const paneKey = btn.dataset.pane;
        const col = btn.dataset.col;
        const p = state[paneKey];
        if (p.sortBy === col) p.sortAsc = !p.sortAsc;
        else {
          p.sortBy = col;
          p.sortAsc = true;
        }
        document
          .querySelectorAll(`.sort-btn[data-pane="${paneKey}"] .sort-ind`)
          .forEach((sp) => (sp.textContent = ""));
        btn.querySelector(".sort-ind").textContent = p.sortAsc ? "▲" : "▼";
        renderPane(paneKey);
      });
    });

    // Drag & Drop between panes + OS file drop onto phone pane
    const phonePane = $("pane-phone");
    phonePane.addEventListener("dragover", (ev) => {
      if (!state.connected) return;
      ev.preventDefault();
      $("drop-target-label").textContent = state.phone.path;
      $("phone-drop-overlay").classList.remove("hidden");
    });
    phonePane.addEventListener("dragleave", (ev) => {
      if (!phonePane.contains(ev.relatedTarget)) {
        $("phone-drop-overlay").classList.add("hidden");
      }
    });
    phonePane.addEventListener("drop", async (ev) => {
      ev.preventDefault();
      $("phone-drop-overlay").classList.add("hidden");
      if (!state.connected) return;
      const internal = ev.dataTransfer.getData("application/x-minifile-items");
      if (internal) {
        const parsed = JSON.parse(internal);
        if (parsed.fromPane === "local" && parsed.paths.length > 0) {
          queueTransfer("up", parsed.paths);
        }
        return;
      }
      const dropped = await collectDroppedFiles(ev.dataTransfer);
      await uploadFilesMultipart(dropped);
    });

    const localPane = $("pane-local");
    localPane.addEventListener("dragover", (ev) => {
      if (ev.dataTransfer.types.includes("application/x-minifile-items")) {
        ev.preventDefault();
      }
    });
    localPane.addEventListener("drop", (ev) => {
      const internal = ev.dataTransfer.getData("application/x-minifile-items");
      if (!internal) return;
      ev.preventDefault();
      const parsed = JSON.parse(internal);
      if (parsed.fromPane === "phone" && parsed.paths.length > 0) {
        queueTransfer("down", parsed.paths);
      }
    });

    // Editor events
    $("editor-textarea").addEventListener("input", () => {
      state.editor.dirty = true;
      $("editor-dirty").classList.remove("hidden");
      updateEditorGutter();
    });
    $("editor-textarea").addEventListener("click", updateEditorGutter);
    $("editor-textarea").addEventListener("keyup", updateEditorGutter);
    $("editor-textarea").addEventListener("scroll", () => {
      $("editor-gutter").scrollTop = $("editor-textarea").scrollTop;
    });
    $("editor-encoding").addEventListener("change", (ev) => {
      if (!state.editor.dirty) {
        openEditor(state.editor.pane, state.editor.path, $("editor-title").textContent, ev.target.value);
      } else {
        state.editor.encoding = ev.target.value;
        updateEditorGutter();
      }
    });
    $("btn-editor-save").addEventListener("click", saveEditor);
    $("btn-editor-close").addEventListener("click", closeEditor);
    $("btn-editor-find-next").addEventListener("click", () => {
      const needle = $("editor-find").value;
      if (!needle) return;
      const ta = $("editor-textarea");
      const text = ta.value;
      const start = ta.selectionEnd || 0;
      let idx = text.indexOf(needle, start);
      if (idx === -1) idx = text.indexOf(needle, 0);
      if (idx !== -1) {
        ta.focus();
        ta.setSelectionRange(idx, idx + needle.length);
        updateEditorGutter();
      } else {
        showToast("No match found");
      }
    });

    // Transfer panel hide/show
    $("btn-toggle-transfers").addEventListener("click", () => {
      const items = $("transfer-items");
      const hidden = items.classList.toggle("hidden");
      $("btn-toggle-transfers").textContent = hidden ? "Show" : "Hide";
    });

    // Update badge & modal
    $("btn-update-badge").addEventListener("click", () => {
      $("modal-update").classList.remove("hidden");
    });
    $("btn-update-check").addEventListener("click", () => {
      $("modal-update").classList.remove("hidden");
      checkUpdates(false);
    });
    $("btn-force-update-check").addEventListener("click", () => checkUpdates(true));

    // Keyboard shortcuts (F2, Delete, Ctrl+A, Backspace, Enter, Ctrl+S, Escape)
    document.addEventListener("keydown", (ev) => {
      if (state.editor.open) {
        if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === "s") {
          ev.preventDefault();
          saveEditor();
        } else if (ev.key === "Escape") {
          closeEditor();
        }
        return;
      }
      if (["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName || "")) {
        return;
      }
      const paneKey = state.activePane;
      const p = state[paneKey];
      if (ev.key === "F2") {
        ev.preventDefault();
        promptRename(paneKey);
      } else if (ev.key === "Delete") {
        ev.preventDefault();
        promptDelete(paneKey);
      } else if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === "a") {
        ev.preventDefault();
        getFilteredSorted(paneKey).forEach((e) => p.selected.add(e.path));
        renderPane(paneKey);
      } else if (ev.key === "Backspace") {
        ev.preventDefault();
        if (p.parent) {
          if (paneKey === "local") loadLocal(p.parent);
          else loadPhone(p.parent);
        }
      } else if (ev.key === "Enter" && p.selected.size === 1) {
        const onlyPath = Array.from(p.selected)[0];
        const entry = p.entries.find((e) => e.path === onlyPath);
        if (entry) openEntry(paneKey, entry);
      }
    });
  }

  async function init() {
    bindEvents();
    await loadFavorites();
    try {
      const st = await api("/api/status");
      applyStatus(st);
    } catch (_) {}
    await loadLocal("");
    if (state.connected) {
      await loadPhone("/");
    } else {
      renderPane("phone");
    }
    checkUpdates(false);
    setInterval(() => checkUpdates(false), 6 * 3600 * 1000);
  }

  init();
})();
