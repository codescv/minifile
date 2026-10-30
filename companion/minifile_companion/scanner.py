"""Concurrent LAN discovery scanner for MiniFile FTP servers."""

from __future__ import annotations

import ipaddress
import socket
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from typing import Any

MAX_SUBNET_HOSTS = 4096


@dataclass(frozen=True)
class Discovered:
    """A discovered MiniFile FTP server on the local network."""

    host: str
    port: int
    banner: str
    rtt_ms: float

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def detect_local_ipv4() -> str:
    """Determine the primary local IPv4 address without sending packets on the wire."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            # RFC 5737 TEST-NET-1: UDP connect only performs a routing table lookup.
            sock.connect(("192.0.2.1", 80))
            ip = sock.getsockname()[0]
            if ip and not ip.startswith("0."):
                return ip
    except OSError:
        pass

    try:
        hostname = socket.gethostname()
        _, _, addrs = socket.gethostbyname_ex(hostname)
        for addr in addrs:
            if not addr.startswith("127."):
                return addr
    except OSError:
        pass

    return "127.0.0.1"


def detect_local_subnet(local_ip: str | None = None) -> str:
    """Derive a sensible default scan subnet (/24 on LAN, /32 on loopback)."""
    ip = local_ip or detect_local_ipv4()
    try:
        parsed = ipaddress.IPv4Address(ip)
    except ipaddress.AddressValueError:
        return "127.0.0.1/32"
    if parsed.is_loopback:
        return f"{parsed}/32"
    net = ipaddress.IPv4Network(f"{parsed}/24", strict=False)
    return str(net)


def iter_subnet_hosts(subnet: str) -> list[str]:
    """Expand a CIDR subnet or single IP string into host addresses."""
    spec = subnet.strip()
    if not spec:
        raise ValueError("Subnet must not be empty")
    try:
        if "/" not in spec:
            addr = ipaddress.ip_address(spec)
            return [str(addr)]
        net = ipaddress.ip_network(spec, strict=False)
    except ValueError as exc:
        raise ValueError(f"Invalid subnet '{subnet}': {exc}") from exc

    if net.num_addresses > MAX_SUBNET_HOSTS + 2:
        raise ValueError(
            f"Subnet {net} is too large ({net.num_addresses} addresses; max {MAX_SUBNET_HOSTS})"
        )

    if net.num_addresses <= 2:
        return [str(ip) for ip in net]
    return [str(ip) for ip in net.hosts()]


def _read_ftp_banner(sock: socket.socket, deadline: float) -> str:
    """Read an FTP 220 greeting banner (single-line or RFC 959 multi-line)."""
    buf = bytearray()
    while len(buf) < 4096:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        sock.settimeout(remaining)
        try:
            chunk = sock.recv(1024)
        except OSError:
            break
        if not chunk:
            break
        buf.extend(chunk)
        text = buf.decode("utf-8", errors="replace")
        lines = text.splitlines()
        for line in lines:
            # Terminal reply line has 3 digits followed by a space (e.g. "220 ...").
            if len(line) >= 4 and line[:3].isdigit() and line[3] == " ":
                return text.strip()
        if "\n" in text and not (len(lines[0]) >= 4 and lines[0][:3].isdigit() and lines[0][3] == "-"):
            return text.strip()
    return buf.decode("utf-8", errors="replace").strip()


def probe_host(
    host: str,
    port: int = 2121,
    timeout: float = 0.3,
    expected_token: str = "minifile",
) -> Discovered | None:
    """Connect to (host, port), check for a MiniFile FTP 220 banner, and measure RTT."""
    t0 = time.monotonic()
    deadline = t0 + max(0.05, float(timeout))
    try:
        with socket.create_connection((host, int(port)), timeout=timeout) as sock:
            rtt_ms = (time.monotonic() - t0) * 1000.0
            banner = _read_ftp_banner(sock, deadline)
            if banner.startswith("220"):
                try:
                    sock.sendall(b"QUIT\r\n")
                except OSError:
                    pass
    except OSError:
        return None

    if not banner:
        return None
    first_code = banner[:3]
    if first_code != "220":
        return None
    if expected_token and expected_token.lower() not in banner.lower():
        return None
    # Collapse multi-line banner to a clean single summary line if needed
    clean_banner = " | ".join(line.strip() for line in banner.splitlines() if line.strip())
    return Discovered(
        host=host,
        port=int(port),
        banner=clean_banner,
        rtt_ms=round(rtt_ms, 2),
    )


def discover(
    subnet: str | None = None,
    port: int = 2121,
    timeout: float = 0.3,
    workers: int = 128,
) -> list[Discovered]:
    """Concurrently scan a subnet for MiniFile FTP servers."""
    target_subnet = subnet.strip() if subnet and subnet.strip() else detect_local_subnet()
    hosts = iter_subnet_hosts(target_subnet)
    if not hosts:
        return []

    max_workers = max(1, min(int(workers), len(hosts)))
    found: list[Discovered] = []
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {
            pool.submit(probe_host, host, port, timeout): host for host in hosts
        }
        for fut in as_completed(futures):
            res = fut.result()
            if res is not None:
                found.append(res)

    found.sort(key=lambda item: (item.rtt_ms, item.host))
    return found
