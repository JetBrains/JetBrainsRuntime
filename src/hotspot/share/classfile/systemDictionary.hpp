/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_CLASSFILE_SYSTEMDICTIONARY_HPP
#define SHARE_VM_CLASSFILE_SYSTEMDICTIONARY_HPP

#include "classfile/classLoader.hpp"
#include "jvmci/systemDictionary_jvmci.hpp"
#include "oops/objArrayOop.hpp"
#include "oops/symbol.hpp"
#include "runtime/java.hpp"
#include "runtime/reflectionUtils.hpp"
#include "runtime/signature.hpp"
#include "utilities/hashtable.hpp"

// The dictionary in each ClassLoaderData stores all loaded classes, either
// initiatied by its class loader or defined by its class loader:
//
//   class loader -> ClassLoaderData -> [class, protection domain set]
//
// Classes are loaded lazily. The default VM class loader is
// represented as NULL.

// The underlying data structure is an open hash table (Dictionary) per
// ClassLoaderData with a fixed number of buckets. During loading the
// class loader object is locked, (for the VM loader a private lock object is used).
// The global SystemDictionary_lock is held for all additions into the ClassLoaderData
// dictionaries.  TODO: fix lock granularity so that class loading can
// be done concurrently, but only by different loaders.
//
// During loading a placeholder (name, loader) is temporarily placed in
// a side data structure, and is used to detect ClassCircularityErrors
// and to perform verification during GC.  A GC can occur in the midst
// of class loading, as we call out to Java, have to take locks, etc.
//
// When class loading is finished, a new entry is added to the dictionary
// of the class loader and the placeholder is removed. Note that the protection
// domain field of the dictionary entry has not yet been filled in when
// the "real" dictionary entry is created.
//
// Clients of this class who are interested in finding if a class has
// been completely loaded -- not classes in the process of being loaded --
// can read the dictionary unlocked. This is safe because
//    - entries are only deleted at safepoints
//    - readers cannot come to a safepoint while actively examining
//         an entry  (an entry cannot be deleted from under a reader)
//    - entries must be fully formed before they are available to concurrent
//         readers (we must ensure write ordering)
//
// Note that placeholders are deleted at any time, as they are removed
// when a class is completely loaded. Therefore, readers as well as writers
// of placeholders must hold the SystemDictionary_lock.
//

class ClassFileStream;
class Dictionary;
class PlaceholderTable;
class LoaderConstraintTable;
template <MEMFLAGS F> class HashtableBucket;
class ResolutionErrorTable;
class SymbolPropertyTable;
class ProtectionDomainCacheTable;
class ProtectionDomainCacheEntry;
class GCTimer;
class OopStorage;

// Certain classes are preloaded, such as java.lang.Object and java.lang.String.
// They are all "well-known", in the sense that no class loader is allowed
// to provide a different definition.
//
// These klasses must all have names defined in vmSymbols.

#define WK_KLASS_ENUM_NAME(kname)    kname##_knum

// Each well-known class has a short klass name (like object_klass),
// a vmSymbol name (like java_lang_Object), and a flag word
// that makes some minor distinctions, like whether the klass
// is preloaded, optional, release-specific, etc.
// The order of these definitions is significant; it is the order in which
// preloading is actually performed by initialize_preloaded_classes.

#define WK_KLASSES_DO(do_klass)                                                                                          \
  /* well-known classes */                                                                                               \
  do_klass(Object_klass,                                java_lang_Object,                          Pre                 ) \
  do_klass(String_klass,                                java_lang_String,                          Pre                 ) \
  do_klass(Class_klass,                                 java_lang_Class,                           Pre                 ) \
  do_klass(Cloneable_klass,                             java_lang_Cloneable,                       Pre                 ) \
  do_klass(ClassLoader_klass,                           java_lang_ClassLoader,                     Pre                 ) \
  do_klass(Serializable_klass,                          java_io_Serializable,                      Pre                 ) \
  do_klass(System_klass,                                java_lang_System,                          Pre                 ) \
  do_klass(Throwable_klass,                             java_lang_Throwable,                       Pre                 ) \
  do_klass(Error_klass,                                 java_lang_Error,                           Pre                 ) \
  do_klass(ThreadDeath_klass,                           java_lang_ThreadDeath,                     Pre                 ) \
  do_klass(Exception_klass,                             java_lang_Exception,                       Pre                 ) \
  do_klass(RuntimeException_klass,                      java_lang_RuntimeException,                Pre                 ) \
  do_klass(SecurityManager_klass,                       java_lang_SecurityManager,                 Pre                 ) \
  do_klass(ProtectionDomain_klass,                      java_security_ProtectionDomain,            Pre                 ) \
  do_klass(AccessControlContext_klass,                  java_security_AccessControlContext,        Pre                 ) \
  do_klass(SecureClassLoader_klass,                     java_security_SecureClassLoader,           Pre                 ) \
  do_klass(ClassNotFoundException_klass,                java_lang_ClassNotFoundException,          Pre                 ) \
  do_klass(NoClassDefFoundError_klass,                  java_lang_NoClassDefFoundError,            Pre                 ) \
  do_klass(LinkageError_klass,                          java_lang_LinkageError,                    Pre                 ) \
  do_klass(ClassCastException_klass,                    java_lang_ClassCastException,              Pre                 ) \
  do_klass(ArrayStoreException_klass,                   java_lang_ArrayStoreException,             Pre                 ) \
  do_klass(VirtualMachineError_klass,                   java_lang_VirtualMachineError,             Pre                 ) \
  do_klass(OutOfMemoryError_klass,                      java_lang_OutOfMemoryError,                Pre                 ) \
  do_klass(StackOverflowError_klass,                    java_lang_StackOverflowError,              Pre                 ) \
  do_klass(IllegalMonitorStateException_klass,          java_lang_IllegalMonitorStateException,    Pre                 ) \
  do_klass(Reference_klass,                             java_lang_ref_Reference,                   Pre                 ) \
                                                                                                                         \
  /* Preload ref klasses and set reference types */                                                                      \
  do_klass(SoftReference_klass,                         java_lang_ref_SoftReference,               Pre                 ) \
  do_klass(WeakReference_klass,                         java_lang_ref_WeakReference,               Pre                 ) \
  do_klass(FinalReference_klass,                        java_lang_ref_FinalReference,              Pre                 ) \
  do_klass(PhantomReference_klass,                      java_lang_ref_PhantomReference,            Pre                 ) \
  do_klass(Finalizer_klass,                             java_lang_ref_Finalizer,                   Pre                 ) \
                                                                                                                         \
  do_klass(Thread_klass,                                java_lang_Thread,                          Pre                 ) \
  do_klass(ThreadGroup_klass,                           java_lang_ThreadGroup,                     Pre                 ) \
  do_klass(Properties_klass,                            java_util_Properties,                      Pre                 ) \
  do_klass(Module_klass,                                java_lang_Module,                          Pre                 ) \
  do_klass(reflect_AccessibleObject_klass,              java_lang_reflect_AccessibleObject,        Pre                 ) \
  do_klass(reflect_Field_klass,                         java_lang_reflect_Field,                   Pre                 ) \
  do_klass(reflect_Parameter_klass,                     java_lang_reflect_Parameter,               Opt                 ) \
  do_klass(reflect_Method_klass,                        java_lang_reflect_Method,                  Pre                 ) \
  do_klass(reflect_Constructor_klass,                   java_lang_reflect_Constructor,             Pre                 ) \
                                                                                                                         \
  /* NOTE: needed too early in bootstrapping process to have checks based on JDK version */                              \
  /* It's okay if this turns out to be NULL in non-1.4 JDKs. */                                                          \
  do_klass(reflect_MagicAccessorImpl_klass,             reflect_MagicAccessorImpl,                 Opt                 ) \
  do_klass(reflect_MethodAccessorImpl_klass,            reflect_MethodAccessorImpl,                Pre                 ) \
  do_klass(reflect_ConstructorAccessorImpl_klass,       reflect_ConstructorAccessorImpl,           Pre                 ) \
  do_klass(reflect_DelegatingClassLoader_klass,         reflect_DelegatingClassLoader,             Opt                 ) \
  do_klass(reflect_ConstantPool_klass,                  reflect_ConstantPool,                      Opt                 ) \
  do_klass(reflect_UnsafeStaticFieldAccessorImpl_klass, reflect_UnsafeStaticFieldAccessorImpl,     Opt                 ) \
  do_klass(reflect_CallerSensitive_klass,               reflect_CallerSensitive,                   Opt                 ) \
                                                                                                                         \
  /* support for dynamic typing; it's OK if these are NULL in earlier JDKs */                                            \
  do_klass(DirectMethodHandle_klass,                    java_lang_invoke_DirectMethodHandle,       Opt                 ) \
  do_klass(DirectMethodHandle_StaticAccessor_klass,     java_lang_invoke_DirectMethodHandle_StaticAccessor, Opt        ) \
  do_klass(DirectMethodHandle_Accessor_klass,           java_lang_invoke_DirectMethodHandle_Accessor, Opt              ) \
  do_klass(MethodHandle_klass,                          java_lang_invoke_MethodHandle,             Pre                 ) \
  do_klass(VarHandle_klass,                             java_lang_invoke_VarHandle,                Pre                 ) \
  do_klass(MemberName_klass,                            java_lang_invoke_MemberName,               Pre                 ) \
  do_klass(ResolvedMethodName_klass,                    java_lang_invoke_ResolvedMethodName,       Pre                 ) \
  do_klass(MethodHandleNatives_klass,                   java_lang_invoke_MethodHandleNatives,      Pre                 ) \
  do_klass(LambdaForm_klass,                            java_lang_invoke_LambdaForm,               Opt                 ) \
  do_klass(MethodType_klass,                            java_lang_invoke_MethodType,               Pre                 ) \
  do_klass(BootstrapMethodError_klass,                  java_lang_BootstrapMethodError,            Pre                 ) \
  do_klass(CallSite_klass,                              java_lang_invoke_CallSite,                 Pre                 ) \
  do_klass(Context_klass,                               java_lang_invoke_MethodHandleNatives_CallSiteContext, Pre      ) \
  do_klass(ConstantCallSite_klass,                      java_lang_invoke_ConstantCallSite,         Pre                 ) \
  do_klass(MutableCallSite_klass,                       java_lang_invoke_MutableCallSite,          Pre                 ) \
  do_klass(VolatileCallSite_klass,                      java_lang_invoke_VolatileCallSite,         Pre                 ) \
  /* Note: MethodHandle must be first, and VolatileCallSite last in group */                                             \
                                                                                                                         \
  do_klass(AssertionStatusDirectives_klass,             java_lang_AssertionStatusDirectives,       Pre                 ) \
  do_klass(StringBuffer_klass,                          java_lang_StringBuffer,                    Pre                 ) \
  do_klass(StringBuilder_klass,                         java_lang_StringBuilder,                   Pre                 ) \
  do_klass(internal_Unsafe_klass,                       jdk_internal_misc_Unsafe,                  Pre                 ) \
  do_klass(module_Modules_klass,                        jdk_internal_module_Modules,               Pre                 ) \
                                                                                                                         \
  /* support for CDS */                                                                                                  \
  do_klass(ByteArrayInputStream_klass,                  java_io_ByteArrayInputStream,              Pre                 ) \
  do_klass(URL_klass,                                   java_net_URL,                              Pre                 ) \
  do_klass(Jar_Manifest_klass,                          java_util_jar_Manifest,                    Pre                 ) \
  do_klass(jdk_internal_loader_ClassLoaders_klass,      jdk_internal_loader_ClassLoaders,          Pre                 ) \
  do_klass(jdk_internal_loader_ClassLoaders_AppClassLoader_klass,      jdk_internal_loader_ClassLoaders_AppClassLoader,       Pre ) \
  do_klass(jdk_internal_loader_ClassLoaders_PlatformClassLoader_klass, jdk_internal_loader_ClassLoaders_PlatformClassLoader,  Pre ) \
  do_klass(CodeSource_klass,                            java_security_CodeSource,                  Pre                 ) \
                                                                                                                         \
  do_klass(StackTraceElement_klass,                     java_lang_StackTraceElement,               Opt                 ) \
                                                                                                                         \
  /* It's okay if this turns out to be NULL in non-1.4 JDKs. */                                                          \
  do_klass(nio_Buffer_klass,                            java_nio_Buffer,                           Opt                 ) \
                                                                                                                         \
  /* Stack Walking */                                                                                                    \
  do_klass(StackWalker_klass,                           java_lang_StackWalker,                     Opt                 ) \
  do_klass(AbstractStackWalker_klass,                   java_lang_StackStreamFactory_AbstractStackWalker, Opt          ) \
  do_klass(StackFrameInfo_klass,                        java_lang_StackFrameInfo,                  Opt                 ) \
  do_klass(LiveStackFrameInfo_klass,                    java_lang_LiveStackFrameInfo,              Opt                 ) \
                                                                                                                         \
  /* support for stack dump lock analysis */                                                                             \
  do_klass(java_util_concurrent_locks_AbstractOwnableSynchronizer_klass, java_util_concurrent_locks_AbstractOwnableSynchronizer, Pre ) \
                                                                                                                         \
  /* Preload boxing klasses */                                                                                           \
  do_klass(Boolean_klass,                               java_lang_Boolean,                         Pre                 ) \
  do_klass(Character_klass,                             java_lang_Character,                       Pre                 ) \
  do_klass(Float_klass,                                 java_lang_Float,                           Pre                 ) \
  do_klass(Double_klass,                                java_lang_Double,                          Pre                 ) \
  do_klass(Byte_klass,                                  java_lang_Byte,                            Pre                 ) \
  do_klass(Short_klass,                                 java_lang_Short,                           Pre                 ) \
  do_klass(Integer_klass,                               java_lang_Integer,                         Pre                 ) \
  do_klass(Long_klass,                                  java_lang_Long,                            Pre                 ) \
                                                                                                                         \
  /* JVMCI classes. These are loaded on-demand. */                                                                       \
  JVMCI_WK_KLASSES_DO(do_klass)                                                                                          \
                                                                                                                         \
  /*end*/


class SystemDictionary : AllStatic {
  friend class VMStructs;
  friend class SystemDictionaryHandles;

 public:
  enum WKID {
    NO_WKID = 0,

    #define WK_KLASS_ENUM(name, symbol, ignore_o) WK_KLASS_ENUM_NAME(name), WK_KLASS_ENUM_NAME(symbol) = WK_KLASS_ENUM_NAME(name),
    WK_KLASSES_DO(WK_KLASS_ENUM)
    #undef WK_KLASS_ENUM

    WKID_LIMIT,

#if INCLUDE_JVMCI
    FIRST_JVMCI_WKID = WK_KLASS_ENUM_NAME(JVMCI_klass),
    LAST_JVMCI_WKID  = WK_KLASS_ENUM_NAME(Value_klass),
#endif

    FIRST_WKID = NO_WKID + 1
  };

  enum InitOption {
    Pre,                        // preloaded; error if not present

    // Order is significant.  Options before this point require resolve_or_fail.
    // Options after this point will use resolve_or_null instead.

    Opt,                        // preload tried; NULL if not present
#if INCLUDE_JVMCI
    Jvmci,                      // preload tried; error if not present if JVMCI enabled
#endif
    OPTION_LIMIT,
    CEIL_LG_OPTION_LIMIT = 2    // OPTION_LIMIT <= (1<<CEIL_LG_OPTION_LIMIT)
  };


  // Returns a class with a given class name and class loader.  Loads the
  // class if needed. If not found a NoClassDefFoundError or a
  // ClassNotFoundException is thrown, depending on the value on the
  // throw_error flag.  For most uses the throw_error argument should be set
  // to true.

  static Klass* resolve_or_fail(Symbol* class_name, Handle class_loader, Handle protection_domain, bool throw_error, TRAPS);
  // Convenient call for null loader and protection domain.
  static Klass* resolve_or_fail(Symbol* class_name, bool throw_error, TRAPS);
protected:
  // handle error translation for resolve_or_null results
  static Klass* handle_resolution_exception(Symbol* class_name, bool throw_error, Klass* klass, TRAPS);

public:

  // Returns a class with a given class name and class loader.
  // Loads the class if needed. If not found NULL is returned.
  static Klass* resolve_or_null(Symbol* class_name, Handle class_loader, Handle protection_domain, TRAPS);
  // Version with null loader and protection domain
  static Klass* resolve_or_null(Symbol* class_name, TRAPS);

  // Resolve a superclass or superinterface. Called from ClassFileParser,
  // parse_interfaces, resolve_instance_class_or_null, load_shared_class
  // "child_name" is the class whose super class or interface is being resolved.
  static Klass* resolve_super_or_fail(Symbol* child_name,
                                      Symbol* class_name,
                                      Handle class_loader,
                                      Handle protection_domain,
                                      bool is_superclass,
                                      TRAPS);

  // Parse new stream. This won't update the dictionary or
  // class hierarchy, simply parse the stream. Used by JVMTI RedefineClasses.
  // Also used by Unsafe_DefineAnonymousClass
  static InstanceKlass* parse_stream(Symbol* class_name,
                                     Handle class_loader,
                                     Handle protection_domain,
                                     ClassFileStream* st,
                                     TRAPS) {
    return parse_stream(class_name,
                        class_loader,
                        protection_domain,
                        st,
                        NULL, // host klass
                        NULL, // old class
                        NULL, // cp_patches
                        THREAD);
  }
  static InstanceKlass* parse_stream(Symbol* class_name,
                                     Handle class_loader,
                                     Handle protection_domain,
                                     ClassFileStream* st,
                                     const InstanceKlass* host_klass,
                                     InstanceKlass* old_klass,
                                     GrowableArray<Handle>* cp_patches,
                                     TRAPS);

  // Resolve from stream (called by jni_DefineClass and JVM_DefineClass)
  static InstanceKlass* resolve_from_stream(Symbol* class_name,
                                            Handle class_loader,
                                            Handle protection_domain,
                                            ClassFileStream* st,
                                            InstanceKlass* old_klass,
                                            TRAPS);

  // Lookup an already loaded class. If not found NULL is returned.
  static Klass* find(Symbol* class_name, Handle class_loader, Handle protection_domain, TRAPS);

  // Lookup an already loaded instance or array class.
  // Do not make any queries to class loaders; consult only the cache.
  // If not found NULL is returned.
  static Klass* find_instance_or_array_klass(Symbol* class_name,
                                               Handle class_loader,
                                               Handle protection_domain,
                                               TRAPS);

  // Lookup an instance or array class that has already been loaded
  // either into the given class loader, or else into another class
  // loader that is constrained (via loader constraints) to produce
  // a consistent class.  Do not take protection domains into account.
  // Do not make any queries to class loaders; consult only the cache.
  // Return NULL if the class is not found.
  //
  // This function is a strict superset of find_instance_or_array_klass.
  // This function (the unchecked version) makes a conservative prediction
  // of the result of the checked version, assuming successful lookup.
  // If both functions return non-null, they must return the same value.
  // Also, the unchecked version may sometimes be non-null where the
  // checked version is null.  This can occur in several ways:
  //   1. No query has yet been made to the class loader.
  //   2. The class loader was queried, but chose not to delegate.
  //   3. ClassLoader.checkPackageAccess rejected a proposed protection domain.
  //   4. Loading was attempted, but there was a linkage error of some sort.
  // In all of these cases, the loader constraints on this type are
  // satisfied, and it is safe for classes in the given class loader
  // to manipulate strongly-typed values of the found class, subject
  // to local linkage and access checks.
  static Klass* find_constrained_instance_or_array_klass(Symbol* class_name,
                                                           Handle class_loader,
                                                           TRAPS);

  static void classes_do(MetaspaceClosure* it);
  // Iterate over all methods in all klasses

  static void methods_do(void f(Method*));

  // Garbage collection support

  // Unload (that is, break root links to) all unmarked classes and
  // loaders.  Returns "true" iff something was unloaded.
  static bool do_unloading(GCTimer* gc_timer,
                           bool do_cleaning = true);

  // Used by DumpSharedSpaces only to remove classes that failed verification
  static void remove_classes_in_error_state();

  static int calculate_systemdictionary_size(int loadedclasses);

  // Applies "f->do_oop" to all root oops in the system dictionary.
  static void oops_do(OopClosure* f);

  // System loader lock
  static oop system_loader_lock()           { return _system_loader_lock_obj; }

public:
  // Sharing support.
  static void reorder_dictionary_for_sharing() NOT_CDS_RETURN;
  static void combine_shared_dictionaries();
  static size_t count_bytes_for_buckets();
  static size_t count_bytes_for_table();
  static void copy_buckets(char* top, char* end);
  static void copy_table(char* top, char* end);
  static void set_shared_dictionary(HashtableBucket<mtClass>* t, int length,
                                    int number_of_entries);
  // Printing
  static void print() { return print_on(tty); }
  static void print_on(outputStream* st);
  static void print_shared(outputStream* st);
  static void dump(outputStream* st, bool verbose);

  // Monotonically increasing counter which grows as classes are
  // loaded or modifications such as hot-swapping or setting/removing
  // of breakpoints are performed
  static inline int number_of_modifications()     { assert_locked_or_safepoint(Compile_lock); return _number_of_modifications; }
  // Needed by evolution and breakpoint code
  static inline void notice_modification()        { assert_locked_or_safepoint(Compile_lock); ++_number_of_modifications;      }

  // Verification
  static void verify();

  // Initialization
  static void initialize(TRAPS);

  // Checked fast access to commonly used classes - mostly preloaded
  static InstanceKlass* check_klass(InstanceKlass* k) {
    assert(k != NULL, "klass not loaded");
    return k;
  }

  static InstanceKlass* check_klass_Pre(InstanceKlass* k) { return check_klass(k); }
  static InstanceKlass* check_klass_Opt(InstanceKlass* k) { return k; }

  JVMCI_ONLY(static InstanceKlass* check_klass_Jvmci(InstanceKlass* k) { return k; })

  static bool initialize_wk_klass(WKID id, int init_opt, TRAPS);
  static void initialize_wk_klasses_until(WKID limit_id, WKID &start_id, TRAPS);
  static void initialize_wk_klasses_through(WKID end_id, WKID &start_id, TRAPS) {
    int limit = (int)end_id + 1;
    initialize_wk_klasses_until((WKID) limit, start_id, THREAD);
  }

public:
  #define WK_KLASS_DECLARE(name, symbol, option) \
    static InstanceKlass* name() { return check_klass_##option(_well_known_klasses[WK_KLASS_ENUM_NAME(name)]); } \
    static InstanceKlass** name##_addr() {                                                                       \
      return &SystemDictionary::_well_known_klasses[SystemDictionary::WK_KLASS_ENUM_NAME(name)];           \
    }
  WK_KLASSES_DO(WK_KLASS_DECLARE);
  #undef WK_KLASS_DECLARE

  static InstanceKlass* well_known_klass(WKID id) {
    assert(id >= (int)FIRST_WKID && id < (int)WKID_LIMIT, "oob");
    return _well_known_klasses[id];
  }

  static InstanceKlass** well_known_klass_addr(WKID id) {
    assert(id >= (int)FIRST_WKID && id < (int)WKID_LIMIT, "oob");
    return &_well_known_klasses[id];
  }
  static void well_known_klasses_do(MetaspaceClosure* it);

  // Local definition for direct access to the private array:
  #define WK_KLASS(name) _well_known_klasses[SystemDictionary::WK_KLASS_ENUM_NAME(name)]

  static InstanceKlass* box_klass(BasicType t) {
    assert((uint)t < T_VOID+1, "range check");
    return check_klass(_box_klasses[t]);
  }
  static BasicType box_klass_type(Klass* k);  // inverse of box_klass

  // Enhanced class redefinition
  static void remove_from_hierarchy(InstanceKlass* k);
  static void update_constraints_after_redefinition();

protected:
  // Returns the class loader data to be used when looking up/updating the
  // system dictionary.
  static ClassLoaderData *class_loader_data(Handle class_loader) {
    return ClassLoaderData::class_loader_data(class_loader());
  }

public:
  // Tells whether ClassLoader.checkPackageAccess is present
  static bool has_checkPackageAccess()      { return _has_checkPackageAccess; }

  static bool Parameter_klass_loaded()      { return WK_KLASS(reflect_Parameter_klass) != NULL; }
  static bool Class_klass_loaded()          { return WK_KLASS(Class_klass) != NULL; }
  static bool Cloneable_klass_loaded()      { return WK_KLASS(Cloneable_klass) != NULL; }
  static bool Object_klass_loaded()         { return WK_KLASS(Object_klass) != NULL; }
  static bool ClassLoader_klass_loaded()    { return WK_KLASS(ClassLoader_klass) != NULL; }

  // Returns java system loader
  static oop java_system_loader();

  // Returns java platform loader
  static oop java_platform_loader();

  // Compute the java system and platform loaders
  static void compute_java_loaders(TRAPS);

  // Register a new class loader
  static ClassLoaderData* register_loader(Handle class_loader);
protected:
  // Mirrors for primitive classes (created eagerly)
  static oop check_mirror(oop m) {
    assert(m != NULL, "mirror not initialized");
    return m;
  }

public:
  // Note:  java_lang_Class::primitive_type is the inverse of java_mirror

  // Check class loader constraints
  static bool add_loader_constraint(Symbol* name, Handle loader1,
                                    Handle loader2, TRAPS);
  static Symbol* check_signature_loaders(Symbol* signature, Handle loader1,
                                         Handle loader2, bool is_method, TRAPS);

  // JSR 292
  // find a java.lang.invoke.MethodHandle.invoke* method for a given signature
  // (asks Java to compute it if necessary, except in a compiler thread)
  static methodHandle find_method_handle_invoker(Klass* klass,
                                                 Symbol* name,
                                                 Symbol* signature,
                                                 Klass* accessing_klass,
                                                 Handle *appendix_result,
                                                 Handle *method_type_result,
                                                 TRAPS);
  // for a given signature, find the internal MethodHandle method (linkTo* or invokeBasic)
  // (does not ask Java, since this is a low-level intrinsic defined by the JVM)
  static methodHandle find_method_handle_intrinsic(vmIntrinsics::ID iid,
                                                   Symbol* signature,
                                                   TRAPS);

  // compute java_mirror (java.lang.Class instance) for a type ("I", "[[B", "LFoo;", etc.)
  // Either the accessing_klass or the CL/PD can be non-null, but not both.
  static Handle    find_java_mirror_for_type(Symbol* signature,
                                             Klass* accessing_klass,
                                             Handle class_loader,
                                             Handle protection_domain,
                                             SignatureStream::FailureMode failure_mode,
                                             TRAPS);
  static Handle    find_java_mirror_for_type(Symbol* signature,
                                             Klass* accessing_klass,
                                             SignatureStream::FailureMode failure_mode,
                                             TRAPS) {
    // callee will fill in CL/PD from AK, if they are needed
    return find_java_mirror_for_type(signature, accessing_klass, Handle(), Handle(),
                                     failure_mode, THREAD);
  }


  // fast short-cut for the one-character case:
  static oop       find_java_mirror_for_type(char signature_char);

  // find a java.lang.invoke.MethodType object for a given signature
  // (asks Java to compute it if necessary, except in a compiler thread)
  static Handle    find_method_handle_type(Symbol* signature,
                                           Klass* accessing_klass,
                                           TRAPS);

  // find a java.lang.Class object for a given signature
  static Handle    find_field_handle_type(Symbol* signature,
                                          Klass* accessing_klass,
                                          TRAPS);

  // ask Java to compute a java.lang.invoke.MethodHandle object for a given CP entry
  static Handle    link_method_handle_constant(Klass* caller,
                                               int ref_kind, //e.g., JVM_REF_invokeVirtual
                                               Klass* callee,
                                               Symbol* name,
                                               Symbol* signature,
                                               TRAPS);

  // ask Java to compute a constant by invoking a BSM given a Dynamic_info CP entry
  static Handle    link_dynamic_constant(Klass* caller,
                                         int condy_index,
                                         Handle bootstrap_specifier,
                                         Symbol* name,
                                         Symbol* type,
                                         TRAPS);

  // ask Java to create a dynamic call site, while linking an invokedynamic op
  static methodHandle find_dynamic_call_site_invoker(Klass* caller,
                                                     int indy_index,
                                                     Handle bootstrap_method,
                                                     Symbol* name,
                                                     Symbol* type,
                                                     Handle *appendix_result,
                                                     Handle *method_type_result,
                                                     TRAPS);

  // Record the error when the first attempt to resolve a reference from a constant
  // pool entry to a class fails.
  static void add_resolution_error(const constantPoolHandle& pool, int which, Symbol* error,
                                   Symbol* message);
  static void delete_resolution_error(ConstantPool* pool);
  static Symbol* find_resolution_error(const constantPoolHandle& pool, int which,
                                       Symbol** message);


  static ProtectionDomainCacheEntry* cache_get(Handle protection_domain);

 protected:

  enum Constants {
    _loader_constraint_size = 107,                     // number of entries in constraint table
    _resolution_error_size  = 107,                     // number of entries in resolution error table
    _invoke_method_size     = 139,                     // number of entries in invoke method table
    _shared_dictionary_size = 1009,                    // number of entries in shared dictionary
    _placeholder_table_size = 1009                     // number of entries in hash table for placeholders
  };


  // Static tables owned by the SystemDictionary

  // Hashtable holding placeholders for classes being loaded.
  static PlaceholderTable*       _placeholders;

  // Hashtable holding classes from the shared archive.
  static Dictionary*             _shared_dictionary;

  // Monotonically increasing counter which grows with
  // loading classes as well as hot-swapping and breakpoint setting
  // and removal.
  static int                     _number_of_modifications;

  // Lock object for system class loader
  static oop                     _system_loader_lock_obj;

  // Constraints on class loaders
  static LoaderConstraintTable*  _loader_constraints;

  // Resolution errors
  static ResolutionErrorTable*   _resolution_errors;

  // Invoke methods (JSR 292)
  static SymbolPropertyTable*    _invoke_method_table;

  // ProtectionDomain cache
  static ProtectionDomainCacheTable*   _pd_cache_table;

  // VM weak OopStorage object.
  static OopStorage*             _vm_weak_oop_storage;

protected:
  static void validate_protection_domain(InstanceKlass* klass,
                                         Handle class_loader,
                                         Handle protection_domain, TRAPS);

  friend class VM_PopulateDumpSharedSpace;
  friend class TraversePlaceholdersClosure;
  static Dictionary*         shared_dictionary() { return _shared_dictionary; }
  static PlaceholderTable*   placeholders() { return _placeholders; }
  static LoaderConstraintTable* constraints() { return _loader_constraints; }
  static ResolutionErrorTable* resolution_errors() { return _resolution_errors; }
  static SymbolPropertyTable* invoke_method_table() { return _invoke_method_table; }

  // Basic loading operations
  static Klass* resolve_instance_class_or_null(Symbol* class_name, Handle class_loader, Handle protection_domain, TRAPS);
  static Klass* resolve_array_class_or_null(Symbol* class_name, Handle class_loader, Handle protection_domain, TRAPS);
  static InstanceKlass* handle_parallel_super_load(Symbol* class_name, Symbol* supername, Handle class_loader, Handle protection_domain, Handle lockObject, TRAPS);
  // Wait on SystemDictionary_lock; unlocks lockObject before
  // waiting; relocks lockObject with correct recursion count
  // after waiting, but before reentering SystemDictionary_lock
  // to preserve lock order semantics.
  static void double_lock_wait(Handle lockObject, TRAPS);
  static void define_instance_class(InstanceKlass* k, InstanceKlass* old_klass, TRAPS);
  static InstanceKlass* find_or_define_instance_class(Symbol* class_name,
                                                Handle class_loader,
                                                InstanceKlass* k, TRAPS);
  static bool is_shared_class_visible(Symbol* class_name, InstanceKlass* ik,
                                      Handle class_loader, TRAPS);
  static InstanceKlass* load_shared_class(InstanceKlass* ik,
                                          Handle class_loader,
                                          Handle protection_domain,
                                          TRAPS);
  static InstanceKlass* load_instance_class(Symbol* class_name, Handle class_loader, TRAPS);
  static Handle compute_loader_lock_object(Handle class_loader, TRAPS);
  static void check_loader_lock_contention(Handle loader_lock, TRAPS);
  static bool is_parallelCapable(Handle class_loader);
  static bool is_parallelDefine(Handle class_loader);

public:
  static InstanceKlass* load_shared_class(Symbol* class_name,
                                          Handle class_loader,
                                          TRAPS);
  static bool is_system_class_loader(oop class_loader);
  static bool is_platform_class_loader(oop class_loader);
  static void clear_invoke_method_table();

  // Returns TRUE if the method is a non-public member of class java.lang.Object.
  static bool is_nonpublic_Object_method(Method* m) {
    assert(m != NULL, "Unexpected NULL Method*");
    return !m->is_public() && m->method_holder() == SystemDictionary::Object_klass();
  }

  static void initialize_oop_storage();
  static OopStorage* vm_weak_oop_storage();

protected:
  static InstanceKlass* find_shared_class(Symbol* class_name);

  // Setup link to hierarchy
  static void add_to_hierarchy(InstanceKlass* k, TRAPS);

  // Basic find on loaded classes
  static InstanceKlass* find_class(unsigned int hash,
                                   Symbol* name, Dictionary* dictionary);
  static InstanceKlass* find_class(Symbol* class_name, ClassLoaderData* loader_data);

  // Basic find on classes in the midst of being loaded
  static Symbol* find_placeholder(Symbol* name, ClassLoaderData* loader_data);

  // Add a placeholder for a class being loaded
  static void add_placeholder(int index,
                              Symbol* class_name,
                              ClassLoaderData* loader_data);
  static void remove_placeholder(int index,
                                 Symbol* class_name,
                                 ClassLoaderData* loader_data);

  // Performs cleanups after resolve_super_or_fail. This typically needs
  // to be called on failure.
  // Won't throw, but can block.
  static void resolution_cleanups(Symbol* class_name,
                                  ClassLoaderData* loader_data,
                                  TRAPS);

  // Initialization
  static void initialize_preloaded_classes(TRAPS);

  // Class loader constraints
  static void check_constraints(unsigned int hash,
                                InstanceKlass* k, Handle loader,
                                bool defining, TRAPS);
  static void update_dictionary(unsigned int d_hash,
                                int p_index, unsigned int p_hash,
                                InstanceKlass* k, Handle loader,
                                TRAPS);

  // Variables holding commonly used klasses (preloaded)
  static InstanceKlass* _well_known_klasses[];

  // table of box klasses (int_klass, etc.)
  static InstanceKlass* _box_klasses[T_VOID+1];

private:
  static oop  _java_system_loader;
  static oop  _java_platform_loader;

  static bool _has_checkPackageAccess;
};

#endif // SHARE_VM_CLASSFILE_SYSTEMDICTIONARY_HPP
