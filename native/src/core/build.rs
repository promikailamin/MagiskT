//! Build script for `core-rs`.
//!
//! Generates CXX bindings via [`gen_cxx_binding`] and compiles the
//! protobuf definition for persistent properties.

use std::fs;
use std::path::{Path, PathBuf};

use pb_rs::ConfigBuilder;
use pb_rs::types::FileDescriptor;

use crate::codegen::gen_cxx_binding;

#[path = "../include/codegen.rs"]
mod codegen;

/// Directory the generated protobuf sources are published to.
const PROTO_DIR: &str = "resetprop/proto";
/// The protobuf definition to compile.
const PROTO_FILE: &str = "persistent_properties.proto";
/// Files pb-rs emits for a single protobuf definition.
const PROTO_FILES: [&str; 2] = ["persistent_properties.rs", "mod.rs"];

/// Compile the protobuf definition and publish the generated sources.
///
/// pb-rs writes its output non-atomically: a `File::create` for the module
/// body plus a read-modify-write append for the module index. A build script
/// runs once per target triple and cargo is free to run those invocations
/// concurrently, so generating straight into the source tree lets one
/// invocation observe a half-written file produced by another. That is a
/// build failure with a nonsensical syntax error pointing at generated code.
///
/// Generating into the per-target `OUT_DIR` removes the shared write
/// entirely, and publishing each file with a rename keeps it complete for
/// every reader at all times.
#[allow(clippy::unwrap_used)]
fn gen_proto_sources() {
    let gen_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("proto");
    fs::create_dir_all(&gen_dir).unwrap();

    let cb = ConfigBuilder::new(
        &[PathBuf::from(PROTO_DIR).join(PROTO_FILE)],
        None,
        Some(&gen_dir),
        &[PathBuf::from(".")],
    )
    .unwrap();
    FileDescriptor::run(
        &cb.single_module(true)
            .dont_use_cow(true)
            .generate_getters(true)
            .build(),
    )
    .unwrap();

    for file in PROTO_FILES {
        let src = gen_dir.join(file);
        let dst = Path::new(PROTO_DIR).join(file);
        let bytes = fs::read(&src).unwrap();
        // Only touch the source tree when the contents actually changed, so
        // cargo does not see a needless mtime bump and rebuild the crate.
        if fs::read(&dst).ok().as_deref() == Some(bytes.as_slice()) {
            continue;
        }
        let tmp = PathBuf::from(format!("{}.tmp", dst.display()));
        fs::write(&tmp, bytes).unwrap();
        fs::rename(&tmp, &dst).unwrap();
    }
}

#[allow(clippy::unwrap_used)]
fn main() {
    println!("cargo:rerun-if-changed={PROTO_DIR}/{PROTO_FILE}");

    gen_cxx_binding("core-rs");
    gen_proto_sources();
}
