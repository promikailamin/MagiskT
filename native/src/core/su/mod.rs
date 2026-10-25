//! SU daemon — handles root access requests.
//!
//! This module manages the full lifecycle of a `su` request:
//! - **daemon**: receives the request, decides (denylist only), forks a child,
//!   and invokes `exec_root_shell`.
//! - **db**: denylist-backed root checks used by the Zygisk root hider.
//! - **pts**: PTY pump for interactive root shells (splice-based I/O).

mod daemon;
mod db;
mod pts;

pub use pts::{get_pty_num, pump_tty};
