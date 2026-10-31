"""MiniFile PC Companion — pure-Python CLI, Web Desktop GUI, and FTP transfer client."""

from __future__ import annotations

__version__ = "1.0.8"

from .client import (
    ConnectionAuthError,
    Discovered,
    MiniFileClient,
    MiniFileError,
    PartialTransferError,
    PathSecurityError,
    ReadOnlyServerError,
    RemoteEntry,
    RemoteNotFoundError,
    ServerInfo,
    SyncAction,
    SyncPlan,
    TransferCancelledError,
    discover,
)

__all__ = [
    "__version__",
    "ConnectionAuthError",
    "Discovered",
    "MiniFileClient",
    "MiniFileError",
    "PartialTransferError",
    "PathSecurityError",
    "ReadOnlyServerError",
    "RemoteEntry",
    "RemoteNotFoundError",
    "ServerInfo",
    "SyncAction",
    "SyncPlan",
    "TransferCancelledError",
    "discover",
]
