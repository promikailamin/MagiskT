//! Shared build script logic — generates CXX FFI bindings.
//!
//! Used by every crate's `build.rs` via `#[path = "../include/codegen.rs"] mod codegen;`.
//! Calls `cxx-gen` to produce `<name>.cpp` and `<name>.hpp` from `lib.rs`.

use std::fmt::Display;
use std::path::{Path, PathBuf};
use std::{fs, io, process};

use cxx_gen::{Include, IncludeKind, Opt};

trait ResultExt<T> {
    fn ok_or_exit(self) -> T;
}

impl<T, E: Display> ResultExt<T> for Result<T, E> {
    fn ok_or_exit(self) -> T {
        match self {
            Ok(r) => r,
            Err(e) => {
                eprintln!("error occurred: {e}");
                process::exit(1);
            }
        }
    }
}

fn write_if_diff<P: AsRef<Path>>(path: P, bytes: &[u8]) -> io::Result<()> {
    let path = path.as_ref();
    if let Ok(orig) = fs::read(path) {
        // Do not modify the file if content is the same to make incremental build more optimal
        if orig.as_slice() == bytes {
            return Ok(());
        }
    }
    // A build script runs once per target triple and cargo may run those
    // concurrently, so the file must never be observable in a partial state.
    // Write to a sibling temp file and rename it into place: readers either
    // see the old file or the complete new one.
    let tmp = PathBuf::from(format!("{}.tmp", path.display()));
    fs::write(&tmp, bytes)?;
    match fs::rename(&tmp, path) {
        Ok(()) => Ok(()),
        Err(e) => {
            fs::remove_file(&tmp).ok();
            Err(e)
        }
    }
}

pub fn gen_cxx_binding(name: &str) {
    println!("cargo:rerun-if-changed=lib.rs");
    let mut opt = Opt::default();
    opt.cxx_impl_annotations = Some("[[gnu::always_inline]]".to_string());
    opt.include.push(Include {
        path: "rust/cxx.h".to_string(),
        kind: IncludeKind::Bracketed,
    });
    let code = cxx_gen::generate_header_and_cc_with_path("lib.rs", &opt);
    write_if_diff(format!("{name}.cpp"), code.implementation.as_slice()).ok_or_exit();
    write_if_diff(format!("{name}.hpp"), code.header.as_slice()).ok_or_exit();
}
