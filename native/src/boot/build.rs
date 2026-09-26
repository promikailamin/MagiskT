//! Build script for `boot-rs`.
//!
//! Generates CXX bindings and compiles the protobuf definition
//! (`update_metadata.proto`) for payload extraction.

use crate::codegen::gen_cxx_binding;
use crate::protocodegen::gen_proto_sources;

#[path = "../include/codegen.rs"]
mod codegen;

#[path = "../include/protocodegen.rs"]
mod protocodegen;

/// Directory the generated protobuf sources are published to.
const PROTO_DIR: &str = "proto";
/// The protobuf definition to compile.
const PROTO_FILE: &str = "update_metadata.proto";

fn main() {
    println!("cargo:rerun-if-changed={PROTO_DIR}/{PROTO_FILE}");

    gen_cxx_binding("boot-rs");
    gen_proto_sources(PROTO_DIR, PROTO_FILE);
}
