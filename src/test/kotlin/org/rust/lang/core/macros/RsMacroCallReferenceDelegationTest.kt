/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import org.rust.*
import org.rust.ide.experiments.RsExperiments
import org.rust.lang.core.resolve.RsResolveTestBase
import org.rust.lang.core.resolve.ref.RsReferenceBase

@ExpandMacros
@CheckTestmarkHit(RsReferenceBase.Testmarks.DelegatedToMacroExpansion::class)
class RsMacroCallReferenceDelegationTest : RsResolveTestBase() {
    fun `test item context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        foo! {
            type T = X;
        }          //^
    """)

    fun `test statement context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        fn main () {
            foo! {
                type T = X;
            };         //^
        }
    """)

    fun `test expression context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        fn main () {
            let a = foo!(X);
        }              //^
    """)

    fun `test type context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        type T = foo!(X);
                    //^
    """)

    fun `test pattern context`() = checkByCode("""
        const X: i32 = 0;
            //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        fn main() {
            match 0 {
                foo!(X) => {}
                   //^
                _ => {}
            }
        }
    """)

    fun `test lifetime`() = checkByCode("""
        macro_rules! foo {
            ($ i:item) => { $ i };
        }
        struct S<'a>(&'a u8);
        impl<'a> S<'a> {
            //X
            foo! {
                fn foo(&self) -> &'a u8 {}
            }                    //^
        }
    """)

    fun `test 2-segment path 1`() = checkByCode("""
        mod foo {
          //X
            pub struct X;
        }
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        foo! {
            type T = foo::X;
        }          //^
    """)

    fun `test 2-segment path 2`() = checkByCode("""
        mod foo {
            pub struct X;
        }            //X
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        foo! {
            type T = foo::X;
        }               //^
    """)

    @ExpandMacros(MacroExpansionScope.WORKSPACE)
    @WithExperimentalFeatures(RsExperiments.PROC_MACROS)
    @ProjectDescriptor(WithProcMacroRustProjectDescriptor::class)
    fun `test path reference in attr proc macro`() = checkByCode("""
        use test_proc_macros::attr_add_to_fn_beginning;
        mod foo {
            pub struct X;
        }            //X
        #[attr_add_to_fn_beginning(use foo::X;)]
        fn main() {
            let _ = X;
        }         //^
    """)

    @ExpandMacros(MacroExpansionScope.WORKSPACE)
    @WithExperimentalFeatures(RsExperiments.PROC_MACROS)
    @ProjectDescriptor(WithProcMacroRustProjectDescriptor::class)
    fun `test pat binding reference in attr proc macro`() = checkByCode("""
        use test_proc_macros::attr_add_to_fn_beginning;
        mod foo {
            pub const C: i32 = 1;
        }           //X
        #[attr_add_to_fn_beginning(use foo::C;)]
        fn main() {
            if let C = 1 {}
        }        //^
    """)

    @ExpandMacros(MacroExpansionScope.WORKSPACE)
    @WithExperimentalFeatures(RsExperiments.PROC_MACROS)
    @ProjectDescriptor(WithProcMacroRustProjectDescriptor::class)
    fun `test macro path reference in attr proc macro`() = checkByCode("""
        use test_proc_macros::attr_add_to_fn_beginning;
        mod foo {
            macro_rules! _bar { () => {}; }
                        //X
            pub use _bar as bar;
        }
        #[attr_add_to_fn_beginning(use foo::bar;)]
        fn main() {
            bar!();
        } //^
    """)
}
