//! SU daemon — handles root access requests.
//!
//! This module manages the full lifecycle of a `su` request:
//! - **daemon**: receives the request, forks a child, invokes `exec_root_shell`.
//! - **connect**: communicates with the Magisk Manager app over FIFO / binder
//!   for user consent (allow/deny), logging, and notifications.
//! - **db**: queries the SQLite policies database for per-app root settings.
//! - **pts**: PTY pump for interactive root shells (splice-based I/O).

mod connect;
mod daemon;
mod db;
mod pts;

pub use daemon::SuInfo;
pub use pts::{get_pty_num, pump_tty};
