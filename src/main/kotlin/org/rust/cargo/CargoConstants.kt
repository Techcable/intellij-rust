/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo

object CargoConstants {

    const val MANIFEST_FILE = "Cargo.toml"
    const val XARGO_MANIFEST_FILE = "Xargo.toml"
    const val LOCK_FILE = "Cargo.lock"

    const val CONFIG_FILE = "config"
    const val CONFIG_TOML_FILE = "config.toml"

    const val TOOLCHAIN_FILE = "rust-toolchain"
    const val TOOLCHAIN_TOML_FILE = "rust-toolchain.toml"

    const val RUST_BACKTRACE_ENV_VAR = "RUST_BACKTRACE"

    object ProjectLayout {
        val sources = listOf("src", "examples")
        val tests = listOf("tests", "benches")
        const val target = "target"
    }
}
