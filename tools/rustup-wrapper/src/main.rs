//! Rustup wrapper for the Magisk custom Rust toolchain.
//!
//! The `rustup component list` command fails with custom toolchains
//! ("toolchain 'magisk' does not support components"), but several IDEs
//! use it to check component availability (clippy, rustfmt, etc.).
//! This wrapper retries failed component commands with the nightly channel.

use std::env;
use std::path::Path;
use std::process::{Command, Stdio};

use home::cargo_home;

fn main() -> std::io::Result<()> {
    let exe = env::args().next().unwrap();
    let exe = Path::new(&exe).file_name().unwrap().to_str().unwrap();
    let real_exe = cargo_home()?.join("bin").join(exe);
    let argv: Vec<String> = env::args().skip(1).collect();

    if exe.starts_with("rustup") && argv.iter().any(|s| s == "component") {
        let status = Command::new(&real_exe)
            .args(&argv)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()?;
        if !status.success() {
            let mut cmd = Command::new(&real_exe);
            // Hardcode to use the nightly channel
            cmd.arg("+nightly");
            // Remove any explicit channel specification
            cmd.args(argv.iter().filter(|s| !s.starts_with('+')));
            return cmd.status().map(|_| ());
        }
    }

    // Simply pass through
    Command::new(&real_exe).args(argv.iter()).status().map(|_| ())
}
