//! Build script for the `base` crate — generates CXX FFI bindings.

use crate::codegen::gen_cxx_binding;

#[path = "../include/codegen.rs"]
mod codegen;

fn main() {
    gen_cxx_binding("base-rs");
}
