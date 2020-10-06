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

#include "precompiled.hpp"
#include "jvm.h"
#include "aot/aotLoader.hpp"
#include "classfile/classFileParser.hpp"
#include "classfile/classFileStream.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/classLoaderData.inline.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/moduleEntry.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/systemDictionaryShared.hpp"
#include "classfile/verifier.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/dependencyContext.hpp"
#include "compiler/compileBroker.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "interpreter/oopMapCache.hpp"
#include "interpreter/rewriter.hpp"
#include "jvmtifiles/jvmti.h"
#include "logging/log.hpp"
#include "logging/logMessage.hpp"
#include "logging/logStream.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/heapInspection.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/metadataFactory.hpp"
#include "memory/metaspaceClosure.hpp"
#include "memory/metaspaceShared.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "oops/fieldStreams.hpp"
#include "oops/instanceClassLoaderKlass.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceMirrorKlass.hpp"
#include "oops/instanceOop.hpp"
#include "oops/klass.inline.hpp"
#include "oops/method.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "prims/jvmtiExport.hpp"
#include "prims/jvmtiRedefineClasses.hpp"
#include "prims/jvmtiThreadState.hpp"
#include "prims/methodComparator.hpp"
#include "runtime/atomic.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/thread.inline.hpp"
#include "services/classLoadingService.hpp"
#include "services/threadService.hpp"
#include "utilities/dtrace.hpp"
#include "utilities/macros.hpp"
#include "utilities/stringUtils.hpp"
#ifdef COMPILER1
#include "c1/c1_Compiler.hpp"
#endif

#ifdef DTRACE_ENABLED


#define HOTSPOT_CLASS_INITIALIZATION_required HOTSPOT_CLASS_INITIALIZATION_REQUIRED
#define HOTSPOT_CLASS_INITIALIZATION_recursive HOTSPOT_CLASS_INITIALIZATION_RECURSIVE
#define HOTSPOT_CLASS_INITIALIZATION_concurrent HOTSPOT_CLASS_INITIALIZATION_CONCURRENT
#define HOTSPOT_CLASS_INITIALIZATION_erroneous HOTSPOT_CLASS_INITIALIZATION_ERRONEOUS
#define HOTSPOT_CLASS_INITIALIZATION_super__failed HOTSPOT_CLASS_INITIALIZATION_SUPER_FAILED
#define HOTSPOT_CLASS_INITIALIZATION_clinit HOTSPOT_CLASS_INITIALIZATION_CLINIT
#define HOTSPOT_CLASS_INITIALIZATION_error HOTSPOT_CLASS_INITIALIZATION_ERROR
#define HOTSPOT_CLASS_INITIALIZATION_end HOTSPOT_CLASS_INITIALIZATION_END
#define DTRACE_CLASSINIT_PROBE(type, thread_type)                \
  {                                                              \
    char* data = NULL;                                           \
    int len = 0;                                                 \
    Symbol* clss_name = name();                                  \
    if (clss_name != NULL) {                                     \
      data = (char*)clss_name->bytes();                          \
      len = clss_name->utf8_length();                            \
    }                                                            \
    HOTSPOT_CLASS_INITIALIZATION_##type(                         \
      data, len, (void*)class_loader(), thread_type);            \
  }

#define DTRACE_CLASSINIT_PROBE_WAIT(type, thread_type, wait)     \
  {                                                              \
    char* data = NULL;                                           \
    int len = 0;                                                 \
    Symbol* clss_name = name();                                  \
    if (clss_name != NULL) {                                     \
      data = (char*)clss_name->bytes();                          \
      len = clss_name->utf8_length();                            \
    }                                                            \
    HOTSPOT_CLASS_INITIALIZATION_##type(                         \
      data, len, (void*)class_loader(), thread_type, wait);      \
  }

#else //  ndef DTRACE_ENABLED

#define DTRACE_CLASSINIT_PROBE(type, thread_type)
#define DTRACE_CLASSINIT_PROBE_WAIT(type, thread_type, wait)

#endif //  ndef DTRACE_ENABLED

static inline bool is_class_loader(const Symbol* class_name,
                                   const ClassFileParser& parser) {
  assert(class_name != NULL, "invariant");

  if (class_name == vmSymbols::java_lang_ClassLoader()) {
    return true;
  }

  if (SystemDictionary::ClassLoader_klass_loaded()) {
    const Klass* const super_klass = parser.super_klass();
    if (super_klass != NULL) {
      if (super_klass->is_subtype_of(SystemDictionary::ClassLoader_klass())) {
        return true;
      }
    }
  }
  return false;
}

// called to verify that k is a member of this nest
bool InstanceKlass::has_nest_member(InstanceKlass* k, TRAPS) const {
  if (_nest_members == NULL || _nest_members == Universe::the_empty_short_array()) {
    if (log_is_enabled(Trace, class, nestmates)) {
      ResourceMark rm(THREAD);
      log_trace(class, nestmates)("Checked nest membership of %s in non-nest-host class %s",
                                  k->external_name(), this->external_name());
    }
    return false;
  }

  if (log_is_enabled(Trace, class, nestmates)) {
    ResourceMark rm(THREAD);
    log_trace(class, nestmates)("Checking nest membership of %s in %s",
                                k->external_name(), this->external_name());
  }

  // Check names first and if they match then check actual klass. This avoids
  // resolving anything unnecessarily.
  for (int i = 0; i < _nest_members->length(); i++) {
    int cp_index = _nest_members->at(i);
    Symbol* name = _constants->klass_name_at(cp_index);
    if (name == k->name()) {
      log_trace(class, nestmates)("- Found it at nest_members[%d] => cp[%d]", i, cp_index);

      // Names match so check actual klass - this may trigger class loading if
      // it doesn't match (though that should be impossible). But to be safe we
      // have to check for a compiler thread executing here.
      if (!THREAD->can_call_java() && !_constants->tag_at(cp_index).is_klass()) {
        log_trace(class, nestmates)("- validation required resolution in an unsuitable thread");
        return false;
      }

      Klass* k2 = _constants->klass_at(cp_index, CHECK_false);
      k2 = k2->newest_version();
      if (k2 == k) {
        log_trace(class, nestmates)("- class is listed as a nest member");
        return true;
      } else {
        // same name but different klass!
        log_trace(class, nestmates)(" - klass comparison failed!");
        // can't have different classes for the same name, so we're done
        return false;
      }
    }
  }
  log_trace(class, nestmates)("- class is NOT a nest member!");
  return false;
}

// Return nest-host class, resolving, validating and saving it if needed.
// In cases where this is called from a thread that can not do classloading
// (such as a native JIT thread) then we simply return NULL, which in turn
// causes the access check to return false. Such code will retry the access
// from a more suitable environment later.
InstanceKlass* InstanceKlass::nest_host(Symbol* validationException, TRAPS) {
  InstanceKlass* nest_host_k = _nest_host;
  if (nest_host_k == NULL) {
    // need to resolve and save our nest-host class. This could be attempted
    // concurrently but as the result is idempotent and we don't use the class
    // then we do not need any synchronization beyond what is implicitly used
    // during class loading.
    if (_nest_host_index != 0) { // we have a real nest_host
      // Before trying to resolve check if we're in a suitable context
      if (!THREAD->can_call_java() && !_constants->tag_at(_nest_host_index).is_klass()) {
        if (log_is_enabled(Trace, class, nestmates)) {
          ResourceMark rm(THREAD);
          log_trace(class, nestmates)("Rejected resolution of nest-host of %s in unsuitable thread",
                                      this->external_name());
        }
        return NULL;
      }

      if (log_is_enabled(Trace, class, nestmates)) {
        ResourceMark rm(THREAD);
        log_trace(class, nestmates)("Resolving nest-host of %s using cp entry for %s",
                                    this->external_name(),
                                    _constants->klass_name_at(_nest_host_index)->as_C_string());
      }

      Klass* k = _constants->klass_at(_nest_host_index, THREAD);
      if (HAS_PENDING_EXCEPTION) {
        Handle exc_h = Handle(THREAD, PENDING_EXCEPTION);
        if (exc_h->is_a(SystemDictionary::NoClassDefFoundError_klass())) {
          // throw a new CDNFE with the original as its cause, and a clear msg
          ResourceMark rm(THREAD);
          char buf[200];
          CLEAR_PENDING_EXCEPTION;
          jio_snprintf(buf, sizeof(buf),
                       "Unable to load nest-host class (%s) of %s",
                       _constants->klass_name_at(_nest_host_index)->as_C_string(),
                       this->external_name());
          log_trace(class, nestmates)("%s - NoClassDefFoundError", buf);
          THROW_MSG_CAUSE_NULL(vmSymbols::java_lang_NoClassDefFoundError(), buf, exc_h);
        }
        // All other exceptions pass through (OOME, StackOverflowError, LinkageErrors etc).
        return NULL;
      }

      // A valid nest-host is an instance class in the current package that lists this
      // class as a nest member. If any of these conditions are not met we post the
      // requested exception type (if any) and return NULL

      const char* error = NULL;

      // JVMS 5.4.4 indicates package check comes first
      if (is_same_class_package(k)) {

        // Now check actual membership. We can't be a member if our "host" is
        // not an instance class.
        if (k->is_instance_klass()) {
          nest_host_k = InstanceKlass::cast(k);

          bool is_member = nest_host_k->has_nest_member(this, CHECK_NULL);
          if (is_member) {
            // save resolved nest-host value
            _nest_host = nest_host_k;

            if (log_is_enabled(Trace, class, nestmates)) {
              ResourceMark rm(THREAD);
              log_trace(class, nestmates)("Resolved nest-host of %s to %s",
                                          this->external_name(), k->external_name());
            }
            return nest_host_k;
          }
        }
        error = "current type is not listed as a nest member";
      } else {
        error = "types are in different packages";
      }

      if (log_is_enabled(Trace, class, nestmates)) {
        ResourceMark rm(THREAD);
        log_trace(class, nestmates)("Type %s is not a nest member of resolved type %s: %s",
                                    this->external_name(),
                                    k->external_name(),
                                    error);
      }

      if (validationException != NULL && THREAD->can_call_java()) {
        ResourceMark rm(THREAD);
        Exceptions::fthrow(THREAD_AND_LOCATION,
                           validationException,
                           "Type %s is not a nest member of %s: %s",
                           this->external_name(),
                           k->external_name(),
                           error
                           );
      }
      return NULL;
    } else {
      if (log_is_enabled(Trace, class, nestmates)) {
        ResourceMark rm(THREAD);
        log_trace(class, nestmates)("Type %s is not part of a nest: setting nest-host to self",
                                    this->external_name());
      }
      // save resolved nest-host value
      return (_nest_host = this);
    }
  }
  return nest_host_k;
}

// check if 'this' and k are nestmates (same nest_host), or k is our nest_host,
// or we are k's nest_host - all of which is covered by comparing the two
// resolved_nest_hosts
bool InstanceKlass::has_nestmate_access_to(InstanceKlass* k, TRAPS) {

  assert(this != k, "this should be handled by higher-level code");

  // Per JVMS 5.4.4 we first resolve and validate the current class, then
  // the target class k. Resolution exceptions will be passed on by upper
  // layers. IncompatibleClassChangeErrors from membership validation failures
  // will also be passed through.

  Symbol* icce = vmSymbols::java_lang_IncompatibleClassChangeError();
  InstanceKlass* cur_host = nest_host(icce, CHECK_false);
  if (cur_host == NULL) {
    return false;
  }

  Klass* k_nest_host = k->nest_host(icce, CHECK_false);
  if (k_nest_host == NULL) {
    return false;
  }

  bool access = (cur_host == k_nest_host);

  if (log_is_enabled(Trace, class, nestmates)) {
    ResourceMark rm(THREAD);
    log_trace(class, nestmates)("Class %s does %shave nestmate access to %s",
                                this->external_name(),
                                access ? "" : "NOT ",
                                k->external_name());
  }

  return access;
}

InstanceKlass* InstanceKlass::allocate_instance_klass(const ClassFileParser& parser, TRAPS) {
  const int size = InstanceKlass::size(parser.vtable_size(),
                                       parser.itable_size(),
                                       nonstatic_oop_map_size(parser.total_oop_map_count()),
                                       parser.is_interface(),
                                       parser.is_anonymous(),
                                       should_store_fingerprint(parser.is_anonymous()));

  const Symbol* const class_name = parser.class_name();
  assert(class_name != NULL, "invariant");
  ClassLoaderData* loader_data = parser.loader_data();
  assert(loader_data != NULL, "invariant");

  InstanceKlass* ik;

  // Allocation
  if (REF_NONE == parser.reference_type()) {
    if (class_name == vmSymbols::java_lang_Class()) {
      // mirror
      ik = new (loader_data, size, THREAD) InstanceMirrorKlass(parser);
    }
    else if (is_class_loader(class_name, parser)) {
      // class loader
      ik = new (loader_data, size, THREAD) InstanceClassLoaderKlass(parser);
    } else {
      // normal
      ik = new (loader_data, size, THREAD) InstanceKlass(parser, InstanceKlass::_misc_kind_other);
    }
  } else {
    // reference
    ik = new (loader_data, size, THREAD) InstanceRefKlass(parser);
  }

  // Check for pending exception before adding to the loader data and incrementing
  // class count.  Can get OOM here.
  if (HAS_PENDING_EXCEPTION) {
    return NULL;
  }

  return ik;
}


// copy method ordering from resource area to Metaspace
void InstanceKlass::copy_method_ordering(const intArray* m, TRAPS) {
  if (m != NULL) {
    // allocate a new array and copy contents (memcpy?)
    _method_ordering = MetadataFactory::new_array<int>(class_loader_data(), m->length(), CHECK);
    for (int i = 0; i < m->length(); i++) {
      _method_ordering->at_put(i, m->at(i));
    }
  } else {
    _method_ordering = Universe::the_empty_int_array();
  }
}

// create a new array of vtable_indices for default methods
Array<int>* InstanceKlass::create_new_default_vtable_indices(int len, TRAPS) {
  Array<int>* vtable_indices = MetadataFactory::new_array<int>(class_loader_data(), len, CHECK_NULL);
  assert(default_vtable_indices() == NULL, "only create once");
  set_default_vtable_indices(vtable_indices);
  return vtable_indices;
}

InstanceKlass::InstanceKlass(const ClassFileParser& parser, unsigned kind, KlassID id) :
  Klass(id),
  _static_field_size(parser.static_field_size()),
  _nonstatic_oop_map_size(nonstatic_oop_map_size(parser.total_oop_map_count())),
  _itable_len(parser.itable_size()),
  _init_thread(NULL),
  _init_state(allocated),
  _reference_type(parser.reference_type()),
  _nest_members(NULL),
  _nest_host_index(0),
  _nest_host(NULL) {
    set_vtable_length(parser.vtable_size());
    set_kind(kind);
    set_access_flags(parser.access_flags());
    set_is_anonymous(parser.is_anonymous());
    set_layout_helper(Klass::instance_layout_helper(parser.layout_size(),
                                                    false));

    assert(NULL == _methods, "underlying memory not zeroed?");
    assert(is_instance_klass(), "is layout incorrect?");
    assert(size_helper() == parser.layout_size(), "incorrect size_helper?");
}

void InstanceKlass::deallocate_methods(ClassLoaderData* loader_data,
                                       Array<Method*>* methods) {
  if (methods != NULL && methods != Universe::the_empty_method_array() &&
      !methods->is_shared()) {
    for (int i = 0; i < methods->length(); i++) {
      Method* method = methods->at(i);
      if (method == NULL) continue;  // maybe null if error processing
      // Only want to delete methods that are not executing for RedefineClasses.
      // The previous version will point to them so they're not totally dangling
      assert (!method->on_stack(), "shouldn't be called with methods on stack");
      MetadataFactory::free_metadata(loader_data, method);
    }
    MetadataFactory::free_array<Method*>(loader_data, methods);
  }
}

void InstanceKlass::deallocate_interfaces(ClassLoaderData* loader_data,
                                          const Klass* super_klass,
                                          Array<Klass*>* local_interfaces,
                                          Array<Klass*>* transitive_interfaces) {
  // Only deallocate transitive interfaces if not empty, same as super class
  // or same as local interfaces.  See code in parseClassFile.
  Array<Klass*>* ti = transitive_interfaces;
  if (ti != Universe::the_empty_klass_array() && ti != local_interfaces) {
    // check that the interfaces don't come from super class
    Array<Klass*>* sti = (super_klass == NULL) ? NULL :
                    InstanceKlass::cast(super_klass)->transitive_interfaces();
    if (ti != sti && ti != NULL && !ti->is_shared()) {
      MetadataFactory::free_array<Klass*>(loader_data, ti);
    }
  }

  // local interfaces can be empty
  if (local_interfaces != Universe::the_empty_klass_array() &&
      local_interfaces != NULL && !local_interfaces->is_shared()) {
    MetadataFactory::free_array<Klass*>(loader_data, local_interfaces);
  }
}

// This function deallocates the metadata and C heap pointers that the
// InstanceKlass points to.
void InstanceKlass::deallocate_contents(ClassLoaderData* loader_data) {

  // Orphan the mirror first, CMS thinks it's still live.
  if (java_mirror() != NULL) {
    java_lang_Class::set_klass(java_mirror(), NULL);
  }

  // Also remove mirror from handles
  loader_data->remove_handle(_java_mirror);

  // Need to take this class off the class loader data list.
  loader_data->remove_class(this);

  // The array_klass for this class is created later, after error handling.
  // For class redefinition, we keep the original class so this scratch class
  // doesn't have an array class.  Either way, assert that there is nothing
  // to deallocate.
  assert(array_klasses() == NULL, "array classes shouldn't be created for this class yet");

  // Release C heap allocated data that this might point to, which includes
  // reference counting symbol names.
  release_C_heap_structures();

  deallocate_methods(loader_data, methods());
  set_methods(NULL);

  if (method_ordering() != NULL &&
      method_ordering() != Universe::the_empty_int_array() &&
      !method_ordering()->is_shared()) {
    MetadataFactory::free_array<int>(loader_data, method_ordering());
  }
  set_method_ordering(NULL);

  // default methods can be empty
  if (default_methods() != NULL &&
      default_methods() != Universe::the_empty_method_array() &&
      !default_methods()->is_shared()) {
    MetadataFactory::free_array<Method*>(loader_data, default_methods());
  }
  // Do NOT deallocate the default methods, they are owned by superinterfaces.
  set_default_methods(NULL);

  // default methods vtable indices can be empty
  if (default_vtable_indices() != NULL &&
      !default_vtable_indices()->is_shared()) {
    MetadataFactory::free_array<int>(loader_data, default_vtable_indices());
  }
  set_default_vtable_indices(NULL);


  // This array is in Klass, but remove it with the InstanceKlass since
  // this place would be the only caller and it can share memory with transitive
  // interfaces.
  if (secondary_supers() != NULL &&
      secondary_supers() != Universe::the_empty_klass_array() &&
      secondary_supers() != transitive_interfaces() &&
      !secondary_supers()->is_shared()) {
    MetadataFactory::free_array<Klass*>(loader_data, secondary_supers());
  }
  set_secondary_supers(NULL);

  deallocate_interfaces(loader_data, super(), local_interfaces(), transitive_interfaces());
  set_transitive_interfaces(NULL);
  set_local_interfaces(NULL);

  if (fields() != NULL && !fields()->is_shared()) {
    MetadataFactory::free_array<jushort>(loader_data, fields());
  }
  set_fields(NULL, 0);

  // If a method from a redefined class is using this constant pool, don't
  // delete it, yet.  The new class's previous version will point to this.
  if (constants() != NULL) {
    assert (!constants()->on_stack(), "shouldn't be called if anything is onstack");
    if (!constants()->is_shared()) {
      MetadataFactory::free_metadata(loader_data, constants());
    }
    // Delete any cached resolution errors for the constant pool
    SystemDictionary::delete_resolution_error(constants());

    set_constants(NULL);
  }

  if (inner_classes() != NULL &&
      inner_classes() != Universe::the_empty_short_array() &&
      !inner_classes()->is_shared()) {
    MetadataFactory::free_array<jushort>(loader_data, inner_classes());
  }
  set_inner_classes(NULL);

  if (nest_members() != NULL &&
      nest_members() != Universe::the_empty_short_array() &&
      !nest_members()->is_shared()) {
    MetadataFactory::free_array<jushort>(loader_data, nest_members());
  }
  set_nest_members(NULL);

  // We should deallocate the Annotations instance if it's not in shared spaces.
  if (annotations() != NULL && !annotations()->is_shared()) {
    MetadataFactory::free_metadata(loader_data, annotations());
  }
  set_annotations(NULL);
}

bool InstanceKlass::should_be_initialized() const {
  return !is_initialized();
}

klassItable InstanceKlass::itable() const {
  return klassItable(const_cast<InstanceKlass*>(this));
}

void InstanceKlass::eager_initialize(Thread *thread) {
  if (!EagerInitialization) return;

  if (this->is_not_initialized()) {
    // abort if the the class has a class initializer
    if (this->class_initializer() != NULL) return;

    // abort if it is java.lang.Object (initialization is handled in genesis)
    Klass* super_klass = super();
    if (super_klass == NULL) return;

    // abort if the super class should be initialized
    if (!InstanceKlass::cast(super_klass)->is_initialized()) return;

    // call body to expose the this pointer
    eager_initialize_impl();
  }
}

// JVMTI spec thinks there are signers and protection domain in the
// instanceKlass.  These accessors pretend these fields are there.
// The hprof specification also thinks these fields are in InstanceKlass.
oop InstanceKlass::protection_domain() const {
  // return the protection_domain from the mirror
  return java_lang_Class::protection_domain(java_mirror());
}

// To remove these from requires an incompatible change and CCC request.
objArrayOop InstanceKlass::signers() const {
  // return the signers from the mirror
  return java_lang_Class::signers(java_mirror());
}

oop InstanceKlass::init_lock() const {
  // return the init lock from the mirror
  oop lock = java_lang_Class::init_lock(java_mirror());
  // Prevent reordering with any access of initialization state
  OrderAccess::loadload();
  assert((oop)lock != NULL || !is_not_initialized(), // initialized or in_error state
         "only fully initialized state can have a null lock");
  return lock;
}

// Set the initialization lock to null so the object can be GC'ed.  Any racing
// threads to get this lock will see a null lock and will not lock.
// That's okay because they all check for initialized state after getting
// the lock and return.
void InstanceKlass::fence_and_clear_init_lock() {
  // make sure previous stores are all done, notably the init_state.
  OrderAccess::storestore();
  java_lang_Class::set_init_lock(java_mirror(), NULL);
  assert(!is_not_initialized(), "class must be initialized now");
}

void InstanceKlass::eager_initialize_impl() {
  EXCEPTION_MARK;
  HandleMark hm(THREAD);
  Handle h_init_lock(THREAD, init_lock());
  ObjectLocker ol(h_init_lock, THREAD, h_init_lock() != NULL);

  // abort if someone beat us to the initialization
  if (!is_not_initialized()) return;  // note: not equivalent to is_initialized()

  ClassState old_state = init_state();
  link_class_impl(true, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    // Abort if linking the class throws an exception.

    // Use a test to avoid redundantly resetting the state if there's
    // no change.  Set_init_state() asserts that state changes make
    // progress, whereas here we might just be spinning in place.
    if (old_state != _init_state)
      set_init_state(old_state);
  } else {
    // linking successfull, mark class as initialized
    set_init_state(fully_initialized);
    fence_and_clear_init_lock();
    // trace
    if (log_is_enabled(Info, class, init)) {
      ResourceMark rm(THREAD);
      log_info(class, init)("[Initialized %s without side effects]", external_name());
    }
  }
}


// See "The Virtual Machine Specification" section 2.16.5 for a detailed explanation of the class initialization
// process. The step comments refers to the procedure described in that section.
// Note: implementation moved to static method to expose the this pointer.
void InstanceKlass::initialize(TRAPS) {
  if (this->should_be_initialized()) {
    initialize_impl(CHECK);
    // Note: at this point the class may be initialized
    //       OR it may be in the state of being initialized
    //       in case of recursive initialization!
  } else {
    assert(is_initialized(), "sanity check");
  }
}


bool InstanceKlass::verify_code(bool throw_verifyerror, TRAPS) {
  // 1) Verify the bytecodes
  Verifier::Mode mode =
    throw_verifyerror ? Verifier::ThrowException : Verifier::NoException;
  return Verifier::verify(this, mode, should_verify_class(), THREAD);
}

void InstanceKlass::link_class(TRAPS) {
  assert(is_loaded(), "must be loaded");
  if (!is_linked()) {
    link_class_impl(true, CHECK);
  }
}

// Called to verify that a class can link during initialization, without
// throwing a VerifyError.
bool InstanceKlass::link_class_or_fail(TRAPS) {
  assert(is_loaded(), "must be loaded");
  if (!is_linked()) {
    link_class_impl(false, CHECK_false);
  }
  return is_linked();
}

bool InstanceKlass::link_class_impl(bool throw_verifyerror, TRAPS) {
  if (DumpSharedSpaces && is_in_error_state()) {
    // This is for CDS dumping phase only -- we use the in_error_state to indicate that
    // the class has failed verification. Throwing the NoClassDefFoundError here is just
    // a convenient way to stop repeat attempts to verify the same (bad) class.
    //
    // Note that the NoClassDefFoundError is not part of the JLS, and should not be thrown
    // if we are executing Java code. This is not a problem for CDS dumping phase since
    // it doesn't execute any Java code.
    ResourceMark rm(THREAD);
    Exceptions::fthrow(THREAD_AND_LOCATION,
                       vmSymbols::java_lang_NoClassDefFoundError(),
                       "Class %s, or one of its supertypes, failed class initialization",
                       external_name());
    return false;
  }
  // return if already verified
  if (is_linked()) {
    return true;
  }

  // Timing
  // timer handles recursion
  assert(THREAD->is_Java_thread(), "non-JavaThread in link_class_impl");
  JavaThread* jt = (JavaThread*)THREAD;

  // link super class before linking this class
  Klass* super_klass = super();
  if (super_klass != NULL) {
    if (super_klass->is_interface()) {  // check if super class is an interface
      ResourceMark rm(THREAD);
      Exceptions::fthrow(
        THREAD_AND_LOCATION,
        vmSymbols::java_lang_IncompatibleClassChangeError(),
        "class %s has interface %s as super class",
        external_name(),
        super_klass->external_name()
      );
      return false;
    }

    InstanceKlass* ik_super = InstanceKlass::cast(super_klass);
    ik_super->link_class_impl(throw_verifyerror, CHECK_false);
  }

  // link all interfaces implemented by this class before linking this class
  Array<Klass*>* interfaces = local_interfaces();
  int num_interfaces = interfaces->length();
  for (int index = 0; index < num_interfaces; index++) {
    InstanceKlass* interk = InstanceKlass::cast(interfaces->at(index));
    interk->link_class_impl(throw_verifyerror, CHECK_false);
  }

  // in case the class is linked in the process of linking its superclasses
  if (is_linked()) {
    return true;
  }

  // trace only the link time for this klass that includes
  // the verification time
  PerfClassTraceTime vmtimer(ClassLoader::perf_class_link_time(),
                             ClassLoader::perf_class_link_selftime(),
                             ClassLoader::perf_classes_linked(),
                             jt->get_thread_stat()->perf_recursion_counts_addr(),
                             jt->get_thread_stat()->perf_timers_addr(),
                             PerfClassTraceTime::CLASS_LINK);

  // verification & rewriting
  {
    HandleMark hm(THREAD);
    Handle h_init_lock(THREAD, init_lock());
    ObjectLocker ol(h_init_lock, THREAD, h_init_lock() != NULL);
    // rewritten will have been set if loader constraint error found
    // on an earlier link attempt
    // don't verify or rewrite if already rewritten
    //

    if (!is_linked()) {
      if (!is_rewritten()) {
        // (DCEVM): If class A is being redefined and class B->A (B is extended from A) and B is host class of anonymous class C
        // then second redefinition fails with cannot cast klass exception. So we currently turn off bytecode verification
        // on redefinition.
        if (!AllowEnhancedClassRedefinition || !newest_version()->is_redefining()) {
          bool verify_ok = verify_code(throw_verifyerror, THREAD);
          if (!verify_ok) {
            return false;
          }
        }

        // Just in case a side-effect of verify linked this class already
        // (which can sometimes happen since the verifier loads classes
        // using custom class loaders, which are free to initialize things)
        if (is_linked()) {
          return true;
        }

        // also sets rewritten
        rewrite_class(CHECK_false);
      } else if (is_shared()) {
        SystemDictionaryShared::check_verification_constraints(this, CHECK_false);
      }

      // relocate jsrs and link methods after they are all rewritten
      link_methods(CHECK_false);

      // Initialize the vtable and interface table after
      // methods have been rewritten since rewrite may
      // fabricate new Method*s.
      // also does loader constraint checking
      //
      // initialize_vtable and initialize_itable need to be rerun for
      // a shared class if the class is not loaded by the NULL classloader.
      ClassLoaderData * loader_data = class_loader_data();
      if (!(is_shared() &&
            loader_data->is_the_null_class_loader_data())) {
        ResourceMark rm(THREAD);
        vtable().initialize_vtable(true, CHECK_false);
        itable().initialize_itable(true, CHECK_false);
      }
#ifdef ASSERT
      else {
        vtable().verify(tty, true);
        // In case itable verification is ever added.
        // itable().verify(tty, true);
      }
#endif
      set_init_state(linked);
      // (DCEVM) Must check for old version in order to prevent infinite loops.
      if (JvmtiExport::should_post_class_prepare() && old_version() == NULL /* JVMTI deadlock otherwise */) {
        Thread *thread = THREAD;
        assert(thread->is_Java_thread(), "thread->is_Java_thread()");
        JvmtiExport::post_class_prepare((JavaThread *) thread, this);
      }
    }
  }
  return true;
}

// Rewrite the byte codes of all of the methods of a class.
// The rewriter must be called exactly once. Rewriting must happen after
// verification but before the first method of the class is executed.
void InstanceKlass::rewrite_class(TRAPS) {
  assert(is_loaded(), "must be loaded");
  if (is_rewritten()) {
    assert(is_shared(), "rewriting an unshared class?");
    return;
  }
  Rewriter::rewrite(this, CHECK);
  set_rewritten();
}

// Now relocate and link method entry points after class is rewritten.
// This is outside is_rewritten flag. In case of an exception, it can be
// executed more than once.
void InstanceKlass::link_methods(TRAPS) {
  int len = methods()->length();
  for (int i = len-1; i >= 0; i--) {
    methodHandle m(THREAD, methods()->at(i));

    // Set up method entry points for compiler and interpreter    .
    m->link_method(m, CHECK);
  }
}

// Eagerly initialize superinterfaces that declare default methods (concrete instance: any access)
void InstanceKlass::initialize_super_interfaces(TRAPS) {
  assert (has_nonstatic_concrete_methods(), "caller should have checked this");
  for (int i = 0; i < local_interfaces()->length(); ++i) {
    Klass* iface = local_interfaces()->at(i);
    InstanceKlass* ik = InstanceKlass::cast(iface);

    // Initialization is depth first search ie. we start with top of the inheritance tree
    // has_nonstatic_concrete_methods drives searching superinterfaces since it
    // means has_nonstatic_concrete_methods in its superinterface hierarchy
    if (ik->has_nonstatic_concrete_methods()) {
      ik->initialize_super_interfaces(CHECK);
    }

    // Only initialize() interfaces that "declare" concrete methods.
    if (ik->should_be_initialized() && ik->declares_nonstatic_concrete_methods()) {
      ik->initialize(CHECK);
    }
  }
}

void InstanceKlass::initialize_impl(TRAPS) {
  HandleMark hm(THREAD);

  // Make sure klass is linked (verified) before initialization
  // A class could already be verified, since it has been reflected upon.
  link_class(CHECK);

  DTRACE_CLASSINIT_PROBE(required, -1);

  bool wait = false;

  // refer to the JVM book page 47 for description of steps
  // Step 1
  {
    Handle h_init_lock(THREAD, init_lock());
    ObjectLocker ol(h_init_lock, THREAD, h_init_lock() != NULL);

    Thread *self = THREAD; // it's passed the current thread

    // Step 2
    // If we were to use wait() instead of waitInterruptibly() then
    // we might end up throwing IE from link/symbol resolution sites
    // that aren't expected to throw.  This would wreak havoc.  See 6320309.
    // (DCEVM) Wait also for the old class version to be fully initialized.
    while((is_being_initialized() && !is_reentrant_initialization(self))
        || (old_version() != NULL && InstanceKlass::cast(old_version())->is_being_initialized())) {
        wait = true;
      ol.waitUninterruptibly(CHECK);
    }

    // Step 3
    if (is_being_initialized() && is_reentrant_initialization(self)) {
      DTRACE_CLASSINIT_PROBE_WAIT(recursive, -1, wait);
      return;
    }

    // Step 4
    if (is_initialized()) {
      DTRACE_CLASSINIT_PROBE_WAIT(concurrent, -1, wait);
      return;
    }

    // Step 5
    if (is_in_error_state()) {
      DTRACE_CLASSINIT_PROBE_WAIT(erroneous, -1, wait);
      ResourceMark rm(THREAD);
      const char* desc = "Could not initialize class ";
      const char* className = external_name();
      size_t msglen = strlen(desc) + strlen(className) + 1;
      char* message = NEW_RESOURCE_ARRAY(char, msglen);
      if (NULL == message) {
        // Out of memory: can't create detailed error message
          THROW_MSG(vmSymbols::java_lang_NoClassDefFoundError(), className);
      } else {
        jio_snprintf(message, msglen, "%s%s", desc, className);
          THROW_MSG(vmSymbols::java_lang_NoClassDefFoundError(), message);
      }
    }

    // Step 6
    set_init_state(being_initialized);
    set_init_thread(self);
  }

  // Step 7
  // Next, if C is a class rather than an interface, initialize it's super class and super
  // interfaces.
  if (!is_interface()) {
    Klass* super_klass = super();
    if (super_klass != NULL && super_klass->should_be_initialized()) {
      super_klass->initialize(THREAD);
    }
    // If C implements any interface that declares a non-static, concrete method,
    // the initialization of C triggers initialization of its super interfaces.
    // Only need to recurse if has_nonstatic_concrete_methods which includes declaring and
    // having a superinterface that declares, non-static, concrete methods
    if (!HAS_PENDING_EXCEPTION && has_nonstatic_concrete_methods()) {
      initialize_super_interfaces(THREAD);
    }

    // If any exceptions, complete abruptly, throwing the same exception as above.
    if (HAS_PENDING_EXCEPTION) {
      Handle e(THREAD, PENDING_EXCEPTION);
      CLEAR_PENDING_EXCEPTION;
      {
        EXCEPTION_MARK;
        // Locks object, set state, and notify all waiting threads
        set_initialization_state_and_notify(initialization_error, THREAD);
        CLEAR_PENDING_EXCEPTION;
      }
      DTRACE_CLASSINIT_PROBE_WAIT(super__failed, -1, wait);
      THROW_OOP(e());
    }
  }


  // Look for aot compiled methods for this klass, including class initializer.
  AOTLoader::load_for_klass(this, THREAD);

  // Step 8
  {
    assert(THREAD->is_Java_thread(), "non-JavaThread in initialize_impl");
    JavaThread* jt = (JavaThread*)THREAD;
    DTRACE_CLASSINIT_PROBE_WAIT(clinit, -1, wait);
    // Timer includes any side effects of class initialization (resolution,
    // etc), but not recursive entry into call_class_initializer().
    PerfClassTraceTime timer(ClassLoader::perf_class_init_time(),
                             ClassLoader::perf_class_init_selftime(),
                             ClassLoader::perf_classes_inited(),
                             jt->get_thread_stat()->perf_recursion_counts_addr(),
                             jt->get_thread_stat()->perf_timers_addr(),
                             PerfClassTraceTime::CLASS_CLINIT);
    call_class_initializer(THREAD);
  }

  // Step 9
  if (!HAS_PENDING_EXCEPTION) {
    set_initialization_state_and_notify(fully_initialized, CHECK);
    {
      debug_only(vtable().verify(tty, true);)
    }
  }
  else {
    // Step 10 and 11
    Handle e(THREAD, PENDING_EXCEPTION);
    CLEAR_PENDING_EXCEPTION;
    // JVMTI has already reported the pending exception
    // JVMTI internal flag reset is needed in order to report ExceptionInInitializerError
    JvmtiExport::clear_detected_exception((JavaThread*)THREAD);
    {
      EXCEPTION_MARK;
      set_initialization_state_and_notify(initialization_error, THREAD);
      CLEAR_PENDING_EXCEPTION;   // ignore any exception thrown, class initialization error is thrown below
      // JVMTI has already reported the pending exception
      // JVMTI internal flag reset is needed in order to report ExceptionInInitializerError
      JvmtiExport::clear_detected_exception((JavaThread*)THREAD);
    }
    DTRACE_CLASSINIT_PROBE_WAIT(error, -1, wait);
    if (e->is_a(SystemDictionary::Error_klass())) {
      THROW_OOP(e());
    } else {
      JavaCallArguments args(e);
      THROW_ARG(vmSymbols::java_lang_ExceptionInInitializerError(),
                vmSymbols::throwable_void_signature(),
                &args);
    }
  }
  DTRACE_CLASSINIT_PROBE_WAIT(end, -1, wait);
}


void InstanceKlass::set_initialization_state_and_notify(ClassState state, TRAPS) {
  Handle h_init_lock(THREAD, init_lock());
  if (h_init_lock() != NULL) {
    ObjectLocker ol(h_init_lock, THREAD);
    set_init_thread(NULL); // reset _init_thread before changing _init_state
    set_init_state(state);
    fence_and_clear_init_lock();
    ol.notify_all(CHECK);
  } else {
    assert(h_init_lock() != NULL, "The initialization state should never be set twice");
    set_init_thread(NULL); // reset _init_thread before changing _init_state
    set_init_state(state);
  }
}

Klass* InstanceKlass::implementor() const {
  assert_locked_or_safepoint(Compile_lock);
  Klass** k = adr_implementor();
  if (k == NULL) {
    return NULL;
  } else {
    return *k;
  }
}

void InstanceKlass::set_implementor(Klass* k) {
  assert_lock_strong(Compile_lock);
  assert(is_interface(), "not interface");
  Klass** addr = adr_implementor();
  assert(addr != NULL, "null addr");
  if (addr != NULL) {
    *addr = k;
  }
}

int  InstanceKlass::nof_implementors() const {
  assert_lock_strong(Compile_lock);
  Klass* k = implementor();
  if (k == NULL) {
    return 0;
  } else if (k != this) {
    return 1;
  } else {
    return 2;
  }
}

// The embedded _implementor field can only record one implementor.
// When there are more than one implementors, the _implementor field
// is set to the interface Klass* itself. Following are the possible
// values for the _implementor field:
//   NULL                  - no implementor
//   implementor Klass*    - one implementor
//   self                  - more than one implementor
//
// The _implementor field only exists for interfaces.
void InstanceKlass::add_implementor(Klass* k) {
  assert_lock_strong(Compile_lock);
  assert(is_interface(), "not interface");
  // Filter out my subinterfaces.
  // (Note: Interfaces are never on the subklass list.)
  if (InstanceKlass::cast(k)->is_interface()) return;

  // Filter out subclasses whose supers already implement me.
  // (Note: CHA must walk subclasses of direct implementors
  // in order to locate indirect implementors.)
  Klass* sk = k->super();
  if (sk != NULL && InstanceKlass::cast(sk)->implements_interface(this))
    // We only need to check one immediate superclass, since the
    // implements_interface query looks at transitive_interfaces.
    // Any supers of the super have the same (or fewer) transitive_interfaces.
    return;

  Klass* ik = implementor();
  if (ik == NULL) {
    set_implementor(k);
  } else if (ik != this && ik != k) {
    // There is already an implementor. Use itself as an indicator of
    // more than one implementors.
    set_implementor(this);
  }

  // The implementor also implements the transitive_interfaces
  for (int index = 0; index < local_interfaces()->length(); index++) {
    InstanceKlass::cast(local_interfaces()->at(index))->add_implementor(k);
  }
}

void InstanceKlass::init_implementor() {
  if (is_interface()) {
    set_implementor(NULL);
  }
}

// (DCEVM) - init_implementor() for dcevm
void InstanceKlass::init_implementor_from_redefine() {
  assert(is_interface(), "not interface");
  Klass** addr = adr_implementor();
  assert(addr != NULL, "null addr");
  if (addr != NULL) {
    *addr = NULL;
  }
}

void InstanceKlass::process_interfaces(Thread *thread) {
  // link this class into the implementors list of every interface it implements
  for (int i = local_interfaces()->length() - 1; i >= 0; i--) {
    assert(local_interfaces()->at(i)->is_klass(), "must be a klass");
    InstanceKlass* interf = InstanceKlass::cast(local_interfaces()->at(i));
    assert(interf->is_interface(), "expected interface");
    interf->add_implementor(this);
  }
}

bool InstanceKlass::can_be_primary_super_slow() const {
  if (is_interface())
    return false;
  else
    return Klass::can_be_primary_super_slow();
}

GrowableArray<Klass*>* InstanceKlass::compute_secondary_supers(int num_extra_slots,
                                                               Array<Klass*>* transitive_interfaces) {
  // The secondaries are the implemented interfaces.
  Array<Klass*>* interfaces = transitive_interfaces;
  int num_secondaries = num_extra_slots + interfaces->length();
  if (num_secondaries == 0) {
    // Must share this for correct bootstrapping!
    set_secondary_supers(Universe::the_empty_klass_array());
    return NULL;
  } else if (num_extra_slots == 0) {
    // The secondary super list is exactly the same as the transitive interfaces.
    // Redefine classes has to be careful not to delete this!
    set_secondary_supers(interfaces);
    return NULL;
  } else {
    // Copy transitive interfaces to a temporary growable array to be constructed
    // into the secondary super list with extra slots.
    GrowableArray<Klass*>* secondaries = new GrowableArray<Klass*>(interfaces->length());
    for (int i = 0; i < interfaces->length(); i++) {
      secondaries->push(interfaces->at(i));
    }
    return secondaries;
  }
}

bool InstanceKlass::compute_is_subtype_of(Klass* k) {
  if (k->is_interface()) {
    return implements_interface(k);
  } else {
    return Klass::compute_is_subtype_of(k);
  }
}

bool InstanceKlass::implements_interface(Klass* k) const {
  if (this == k) return true;
  assert(k->is_interface(), "should be an interface class");
  for (int i = 0; i < transitive_interfaces()->length(); i++) {
    if (transitive_interfaces()->at(i) == k) {
      return true;
    }
  }
  return false;
}


// (DCEVM)
bool InstanceKlass::implements_interface_any_version(Klass* k) const {
  k = k->newest_version();
  if (this->newest_version() == k) return true;
  assert(k->is_interface(), "should be an interface class");
  for (int i = 0; i < transitive_interfaces()->length(); i++) {
    if (transitive_interfaces()->at(i)->newest_version() == k) {
      return true;
    }
  }
  return false;
}

bool InstanceKlass::is_same_or_direct_interface(Klass *k) const {
  // Verify direct super interface
  if (this == k) return true;
  assert(k->is_interface(), "should be an interface class");
  for (int i = 0; i < local_interfaces()->length(); i++) {
    if (local_interfaces()->at(i) == k) {
      return true;
    }
  }
  return false;
}

objArrayOop InstanceKlass::allocate_objArray(int n, int length, TRAPS) {
  if (length < 0)  {
    THROW_MSG_0(vmSymbols::java_lang_NegativeArraySizeException(), err_msg("%d", length));
  }
  if (length > arrayOopDesc::max_array_length(T_OBJECT)) {
    report_java_out_of_memory("Requested array size exceeds VM limit");
    JvmtiExport::post_array_size_exhausted();
    THROW_OOP_0(Universe::out_of_memory_error_array_size());
  }
  int size = objArrayOopDesc::object_size(length);
  Klass* ak = array_klass(n, CHECK_NULL);
  objArrayOop o = (objArrayOop)Universe::heap()->array_allocate(ak, size, length,
                                                                /* do_zero */ true, CHECK_NULL);
  return o;
}

instanceOop InstanceKlass::register_finalizer(instanceOop i, TRAPS) {
  if (TraceFinalizerRegistration) {
    tty->print("Registered ");
    i->print_value_on(tty);
    tty->print_cr(" (" INTPTR_FORMAT ") as finalizable", p2i(i));
  }
  instanceHandle h_i(THREAD, i);
  // Pass the handle as argument, JavaCalls::call expects oop as jobjects
  JavaValue result(T_VOID);
  JavaCallArguments args(h_i);
  methodHandle mh (THREAD, Universe::finalizer_register_method());
  JavaCalls::call(&result, mh, &args, CHECK_NULL);
  return h_i();
}

instanceOop InstanceKlass::allocate_instance(TRAPS) {
  bool has_finalizer_flag = has_finalizer(); // Query before possible GC
  int size = size_helper();  // Query before forming handle.

  instanceOop i;

  i = (instanceOop)Universe::heap()->obj_allocate(this, size, CHECK_NULL);
  if (has_finalizer_flag && !RegisterFinalizersAtInit) {
    i = register_finalizer(i, CHECK_NULL);
  }
  return i;
}

instanceHandle InstanceKlass::allocate_instance_handle(TRAPS) {
  return instanceHandle(THREAD, allocate_instance(THREAD));
}

void InstanceKlass::check_valid_for_instantiation(bool throwError, TRAPS) {
  if (is_interface() || is_abstract()) {
    ResourceMark rm(THREAD);
    THROW_MSG(throwError ? vmSymbols::java_lang_InstantiationError()
              : vmSymbols::java_lang_InstantiationException(), external_name());
  }
  if (this == SystemDictionary::Class_klass()) {
    ResourceMark rm(THREAD);
    THROW_MSG(throwError ? vmSymbols::java_lang_IllegalAccessError()
              : vmSymbols::java_lang_IllegalAccessException(), external_name());
  }
}

Klass* InstanceKlass::array_klass_impl(bool or_null, int n, TRAPS) {
  // Need load-acquire for lock-free read
  if (array_klasses_acquire() == NULL) {
    if (or_null) return NULL;

    ResourceMark rm;
    JavaThread *jt = (JavaThread *)THREAD;
    {
      // Atomic creation of array_klasses
      MutexLocker mc(Compile_lock, THREAD);   // for vtables
      MutexLocker ma(MultiArray_lock, THREAD);

      // Check if update has already taken place
      if (array_klasses() == NULL) {
        Klass*    k = ObjArrayKlass::allocate_objArray_klass(class_loader_data(), 1, this, CHECK_NULL);
        // use 'release' to pair with lock-free load
        release_set_array_klasses(k);
      }
    }
  }
  // _this will always be set at this point
  ObjArrayKlass* oak = (ObjArrayKlass*)array_klasses();
  if (or_null) {
    return oak->array_klass_or_null(n);
  }
  return oak->array_klass(n, THREAD);
}

Klass* InstanceKlass::array_klass_impl(bool or_null, TRAPS) {
  return array_klass_impl(or_null, 1, THREAD);
}

static int call_class_initializer_counter = 0;   // for debugging

Method* InstanceKlass::class_initializer() const {
  Method* clinit = find_method(
      vmSymbols::class_initializer_name(), vmSymbols::void_method_signature());
  if (clinit != NULL && clinit->has_valid_initializer_flags()) {
    return clinit;
  }
  return NULL;
}

void InstanceKlass::call_class_initializer(TRAPS) {
  if (ReplayCompiles &&
      (ReplaySuppressInitializers == 1 ||
       (ReplaySuppressInitializers >= 2 && class_loader() != NULL))) {
    // Hide the existence of the initializer for the purpose of replaying the compile
    return;
  }

  methodHandle h_method(THREAD, class_initializer());
  assert(!is_initialized(), "we cannot initialize twice");
  LogTarget(Info, class, init) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    ls.print("%d Initializing ", call_class_initializer_counter++);
    name()->print_value_on(&ls);
    ls.print_cr("%s (" INTPTR_FORMAT ")", h_method() == NULL ? "(no method)" : "", p2i(this));
  }
  if (h_method() != NULL) {
    JavaCallArguments args; // No arguments
    JavaValue result(T_VOID);
    JavaCalls::call(&result, h_method, &args, CHECK); // Static call (no args)
  }
}


void InstanceKlass::mask_for(const methodHandle& method, int bci,
  InterpreterOopMap* entry_for) {
  // Lazily create the _oop_map_cache at first request
  // Lock-free access requires load_acquire.
  OopMapCache* oop_map_cache = OrderAccess::load_acquire(&_oop_map_cache);
  if (oop_map_cache == NULL) {
    MutexLocker x(OopMapCacheAlloc_lock);
    // Check if _oop_map_cache was allocated while we were waiting for this lock
    if ((oop_map_cache = _oop_map_cache) == NULL) {
      oop_map_cache = new OopMapCache();
      // Ensure _oop_map_cache is stable, since it is examined without a lock
      OrderAccess::release_store(&_oop_map_cache, oop_map_cache);
    }
  }
  // _oop_map_cache is constant after init; lookup below does its own locking.
  oop_map_cache->lookup(method, bci, entry_for);
}


bool InstanceKlass::find_local_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const {
  for (JavaFieldStream fs(this); !fs.done(); fs.next()) {
    Symbol* f_name = fs.name();
    Symbol* f_sig  = fs.signature();
    if (f_name == name && f_sig == sig) {
      fd->reinitialize(const_cast<InstanceKlass*>(this), fs.index());
      return true;
    }
  }
  return false;
}


Klass* InstanceKlass::find_interface_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const {
  const int n = local_interfaces()->length();
  for (int i = 0; i < n; i++) {
    Klass* intf1 = local_interfaces()->at(i);
    assert(intf1->is_interface(), "just checking type");
    // search for field in current interface
    if (InstanceKlass::cast(intf1)->find_local_field(name, sig, fd)) {
      assert(fd->is_static(), "interface field must be static");
      return intf1;
    }
    // search for field in direct superinterfaces
    Klass* intf2 = InstanceKlass::cast(intf1)->find_interface_field(name, sig, fd);
    if (intf2 != NULL) return intf2;
  }
  // otherwise field lookup fails
  return NULL;
}


Klass* InstanceKlass::find_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const {
  // search order according to newest JVM spec (5.4.3.2, p.167).
  // 1) search for field in current klass
  if (find_local_field(name, sig, fd)) {
    return const_cast<InstanceKlass*>(this);
  }
  // 2) search for field recursively in direct superinterfaces
  { Klass* intf = find_interface_field(name, sig, fd);
    if (intf != NULL) return intf;
  }
  // 3) apply field lookup recursively if superclass exists
  { Klass* supr = super();
    if (supr != NULL) return InstanceKlass::cast(supr)->find_field(name, sig, fd);
  }
  // 4) otherwise field lookup fails
  return NULL;
}


Klass* InstanceKlass::find_field(Symbol* name, Symbol* sig, bool is_static, fieldDescriptor* fd) const {
  // search order according to newest JVM spec (5.4.3.2, p.167).
  // 1) search for field in current klass
  if (find_local_field(name, sig, fd)) {
    if (fd->is_static() == is_static) return const_cast<InstanceKlass*>(this);
  }
  // 2) search for field recursively in direct superinterfaces
  if (is_static) {
    Klass* intf = find_interface_field(name, sig, fd);
    if (intf != NULL) return intf;
  }
  // 3) apply field lookup recursively if superclass exists
  { Klass* supr = super();
    if (supr != NULL) return InstanceKlass::cast(supr)->find_field(name, sig, is_static, fd);
  }
  // 4) otherwise field lookup fails
  return NULL;
}


bool InstanceKlass::find_local_field_from_offset(int offset, bool is_static, fieldDescriptor* fd) const {
  for (JavaFieldStream fs(this); !fs.done(); fs.next()) {
    if (fs.offset() == offset) {
      fd->reinitialize(const_cast<InstanceKlass*>(this), fs.index());
      if (fd->is_static() == is_static) return true;
    }
  }
  return false;
}


bool InstanceKlass::find_field_from_offset(int offset, bool is_static, fieldDescriptor* fd) const {
  Klass* klass = const_cast<InstanceKlass*>(this);
  while (klass != NULL) {
    if (InstanceKlass::cast(klass)->find_local_field_from_offset(offset, is_static, fd)) {
      return true;
    }
    klass = klass->super();
  }
  return false;
}


void InstanceKlass::methods_do(void f(Method* method)) {
  // Methods aren't stable until they are loaded.  This can be read outside
  // a lock through the ClassLoaderData for profiling
  if (!is_loaded()) {
    return;
  }

  int len = methods()->length();
  for (int index = 0; index < len; index++) {
    Method* m = methods()->at(index);
    assert(m->is_method(), "must be method");
    f(m);
  }
}

//  (DCEVM) Update information contains mapping of fields from old class to the new class.
//  Info is stored on HEAP, you need to call clear_update_information to free the space.
void InstanceKlass::store_update_information(GrowableArray<int> &values) {
  int *arr = NEW_C_HEAP_ARRAY(int, values.length(), mtClass);
  for (int i = 0; i < values.length(); i++) {
    arr[i] = values.at(i);
  }
  set_update_information(arr);
}

void InstanceKlass::clear_update_information() {
  FREE_C_HEAP_ARRAY(int, update_information());
  set_update_information(NULL);
}


void InstanceKlass::do_local_static_fields(FieldClosure* cl) {
  for (JavaFieldStream fs(this); !fs.done(); fs.next()) {
    if (fs.access_flags().is_static()) {
      fieldDescriptor& fd = fs.field_descriptor();
      cl->do_field(&fd);
    }
  }
}


void InstanceKlass::do_local_static_fields(void f(fieldDescriptor*, Handle, TRAPS), Handle mirror, TRAPS) {
  for (JavaFieldStream fs(this); !fs.done(); fs.next()) {
    if (fs.access_flags().is_static()) {
      fieldDescriptor& fd = fs.field_descriptor();
      f(&fd, mirror, CHECK);
    }
  }
}


static int compare_fields_by_offset(int* a, int* b) {
  return a[0] - b[0];
}

void InstanceKlass::do_nonstatic_fields(FieldClosure* cl) {
  InstanceKlass* super = superklass();
  if (super != NULL) {
    super->do_nonstatic_fields(cl);
  }
  fieldDescriptor fd;
  int length = java_fields_count();
  // In DebugInfo nonstatic fields are sorted by offset.
  int* fields_sorted = NEW_C_HEAP_ARRAY(int, 2*(length+1), mtClass);
  int j = 0;
  for (int i = 0; i < length; i += 1) {
    fd.reinitialize(this, i);
    if (!fd.is_static()) {
      fields_sorted[j + 0] = fd.offset();
      fields_sorted[j + 1] = i;
      j += 2;
    }
  }
  if (j > 0) {
    length = j;
    // _sort_Fn is defined in growableArray.hpp.
    qsort(fields_sorted, length/2, 2*sizeof(int), (_sort_Fn)compare_fields_by_offset);
    for (int i = 0; i < length; i += 2) {
      fd.reinitialize(this, fields_sorted[i + 1]);
      assert(!fd.is_static() && fd.offset() == fields_sorted[i], "only nonstatic fields");
      cl->do_field(&fd);
    }
  }
  FREE_C_HEAP_ARRAY(int, fields_sorted);
}


void InstanceKlass::array_klasses_do(void f(Klass* k, TRAPS), TRAPS) {
  if (array_klasses() != NULL)
    ArrayKlass::cast(array_klasses())->array_klasses_do(f, THREAD);
}

void InstanceKlass::array_klasses_do(void f(Klass* k)) {
  if (array_klasses() != NULL)
    ArrayKlass::cast(array_klasses())->array_klasses_do(f);
}

#ifdef ASSERT
static int linear_search(const Array<Method*>* methods,
                         const Symbol* name,
                         const Symbol* signature) {
  const int len = methods->length();
  for (int index = 0; index < len; index++) {
    const Method* const m = methods->at(index);
    assert(m->is_method(), "must be method");
    if (m->signature() == signature && m->name() == name) {
       return index;
    }
  }
  return -1;
}
#endif

static int binary_search(const Array<Method*>* methods, const Symbol* name) {
  int len = methods->length();
  // methods are sorted, so do binary search
  int l = 0;
  int h = len - 1;
  while (l <= h) {
    int mid = (l + h) >> 1;
    Method* m = methods->at(mid);
    assert(m->is_method(), "must be method");
    int res = m->name()->fast_compare(name);
    if (res == 0) {
      return mid;
    } else if (res < 0) {
      l = mid + 1;
    } else {
      h = mid - 1;
    }
  }
  return -1;
}

// find_method looks up the name/signature in the local methods array
Method* InstanceKlass::find_method(const Symbol* name,
                                   const Symbol* signature) const {
  return find_method_impl(name, signature, find_overpass, find_static, find_private);
}

Method* InstanceKlass::find_method_impl(const Symbol* name,
                                        const Symbol* signature,
                                        OverpassLookupMode overpass_mode,
                                        StaticLookupMode static_mode,
                                        PrivateLookupMode private_mode) const {
  return InstanceKlass::find_method_impl(methods(),
                                         name,
                                         signature,
                                         overpass_mode,
                                         static_mode,
                                         private_mode);
}

// find_instance_method looks up the name/signature in the local methods array
// and skips over static methods
Method* InstanceKlass::find_instance_method(const Array<Method*>* methods,
                                            const Symbol* name,
                                            const Symbol* signature,
                                            PrivateLookupMode private_mode) {
  Method* const meth = InstanceKlass::find_method_impl(methods,
                                                 name,
                                                 signature,
                                                 find_overpass,
                                                 skip_static,
                                                 private_mode);
  assert(((meth == NULL) || !meth->is_static()),
    "find_instance_method should have skipped statics");
  return meth;
}

// find_instance_method looks up the name/signature in the local methods array
// and skips over static methods
Method* InstanceKlass::find_instance_method(const Symbol* name,
                                            const Symbol* signature,
                                            PrivateLookupMode private_mode) const {
  return InstanceKlass::find_instance_method(methods(), name, signature, private_mode);
}

// Find looks up the name/signature in the local methods array
// and filters on the overpass, static and private flags
// This returns the first one found
// note that the local methods array can have up to one overpass, one static
// and one instance (private or not) with the same name/signature
Method* InstanceKlass::find_local_method(const Symbol* name,
                                         const Symbol* signature,
                                         OverpassLookupMode overpass_mode,
                                         StaticLookupMode static_mode,
                                         PrivateLookupMode private_mode) const {
  return InstanceKlass::find_method_impl(methods(),
                                         name,
                                         signature,
                                         overpass_mode,
                                         static_mode,
                                         private_mode);
}

// Find looks up the name/signature in the local methods array
// and filters on the overpass, static and private flags
// This returns the first one found
// note that the local methods array can have up to one overpass, one static
// and one instance (private or not) with the same name/signature
Method* InstanceKlass::find_local_method(const Array<Method*>* methods,
                                         const Symbol* name,
                                         const Symbol* signature,
                                         OverpassLookupMode overpass_mode,
                                         StaticLookupMode static_mode,
                                         PrivateLookupMode private_mode) {
  return InstanceKlass::find_method_impl(methods,
                                         name,
                                         signature,
                                         overpass_mode,
                                         static_mode,
                                         private_mode);
}

Method* InstanceKlass::find_method(const Array<Method*>* methods,
                                   const Symbol* name,
                                   const Symbol* signature) {
  return InstanceKlass::find_method_impl(methods,
                                         name,
                                         signature,
                                         find_overpass,
                                         find_static,
                                         find_private);
}

Method* InstanceKlass::find_method_impl(const Array<Method*>* methods,
                                        const Symbol* name,
                                        const Symbol* signature,
                                        OverpassLookupMode overpass_mode,
                                        StaticLookupMode static_mode,
                                        PrivateLookupMode private_mode) {
  int hit = find_method_index(methods, name, signature, overpass_mode, static_mode, private_mode);
  return hit >= 0 ? methods->at(hit): NULL;
}

// true if method matches signature and conforms to skipping_X conditions.
static bool method_matches(const Method* m,
                           const Symbol* signature,
                           bool skipping_overpass,
                           bool skipping_static,
                           bool skipping_private) {
  return ((m->signature() == signature) &&
    (!skipping_overpass || !m->is_overpass()) &&
    (!skipping_static || !m->is_static()) &&
    (!skipping_private || !m->is_private()));
}

// Used directly for default_methods to find the index into the
// default_vtable_indices, and indirectly by find_method
// find_method_index looks in the local methods array to return the index
// of the matching name/signature. If, overpass methods are being ignored,
// the search continues to find a potential non-overpass match.  This capability
// is important during method resolution to prefer a static method, for example,
// over an overpass method.
// There is the possibility in any _method's array to have the same name/signature
// for a static method, an overpass method and a local instance method
// To correctly catch a given method, the search criteria may need
// to explicitly skip the other two. For local instance methods, it
// is often necessary to skip private methods
int InstanceKlass::find_method_index(const Array<Method*>* methods,
                                     const Symbol* name,
                                     const Symbol* signature,
                                     OverpassLookupMode overpass_mode,
                                     StaticLookupMode static_mode,
                                     PrivateLookupMode private_mode) {
  const bool skipping_overpass = (overpass_mode == skip_overpass);
  const bool skipping_static = (static_mode == skip_static);
  const bool skipping_private = (private_mode == skip_private);
  const int hit = binary_search(methods, name);
  if (hit != -1) {
    const Method* const m = methods->at(hit);

    // Do linear search to find matching signature.  First, quick check
    // for common case, ignoring overpasses if requested.
    if (method_matches(m, signature, skipping_overpass, skipping_static, skipping_private)) {
      return hit;
    }

    // search downwards through overloaded methods
    int i;
    for (i = hit - 1; i >= 0; --i) {
        const Method* const m = methods->at(i);
        assert(m->is_method(), "must be method");
        if (m->name() != name) {
          break;
        }
        if (method_matches(m, signature, skipping_overpass, skipping_static, skipping_private)) {
          return i;
        }
    }
    // search upwards
    for (i = hit + 1; i < methods->length(); ++i) {
        const Method* const m = methods->at(i);
        assert(m->is_method(), "must be method");
        if (m->name() != name) {
          break;
        }
        if (method_matches(m, signature, skipping_overpass, skipping_static, skipping_private)) {
          return i;
        }
    }
    // not found
#ifdef ASSERT
    const int index = (skipping_overpass || skipping_static || skipping_private) ? -1 :
      linear_search(methods, name, signature);
    assert(-1 == index, "binary search should have found entry %d", index);
#endif
  }
  return -1;
}

int InstanceKlass::find_method_by_name(const Symbol* name, int* end) const {
  return find_method_by_name(methods(), name, end);
}

int InstanceKlass::find_method_by_name(const Array<Method*>* methods,
                                       const Symbol* name,
                                       int* end_ptr) {
  assert(end_ptr != NULL, "just checking");
  int start = binary_search(methods, name);
  int end = start + 1;
  if (start != -1) {
    while (start - 1 >= 0 && (methods->at(start - 1))->name() == name) --start;
    while (end < methods->length() && (methods->at(end))->name() == name) ++end;
    *end_ptr = end;
    return start;
  }
  return -1;
}

// uncached_lookup_method searches both the local class methods array and all
// superclasses methods arrays, skipping any overpass methods in superclasses,
// and possibly skipping private methods.
Method* InstanceKlass::uncached_lookup_method(const Symbol* name,
                                              const Symbol* signature,
                                              OverpassLookupMode overpass_mode,
                                              PrivateLookupMode private_mode) const {
  OverpassLookupMode overpass_local_mode = overpass_mode;
  const Klass* klass = this;
  while (klass != NULL) {
    Method* const method = InstanceKlass::cast(klass)->find_method_impl(name,
                                                                        signature,
                                                                        overpass_local_mode,
                                                                        find_static,
                                                                        private_mode);
    if (method != NULL) {
      return method;
    }
    klass = klass->super();
    overpass_local_mode = skip_overpass;   // Always ignore overpass methods in superclasses
  }
  return NULL;
}

#ifdef ASSERT
// search through class hierarchy and return true if this class or
// one of the superclasses was redefined
bool InstanceKlass::has_redefined_this_or_super() const {
  const Klass* klass = this;
  while (klass != NULL) {
    if (InstanceKlass::cast(klass)->has_been_redefined()) {
      return true;
    }
    klass = klass->super();
  }
  return false;
}
#endif

// lookup a method in the default methods list then in all transitive interfaces
// Do NOT return private or static methods
Method* InstanceKlass::lookup_method_in_ordered_interfaces(Symbol* name,
                                                         Symbol* signature) const {
  Method* m = NULL;
  if (default_methods() != NULL) {
    m = find_method(default_methods(), name, signature);
  }
  // Look up interfaces
  if (m == NULL) {
    m = lookup_method_in_all_interfaces(name, signature, find_defaults);
  }
  return m;
}

// lookup a method in all the interfaces that this class implements
// Do NOT return private or static methods, new in JDK8 which are not externally visible
// They should only be found in the initial InterfaceMethodRef
Method* InstanceKlass::lookup_method_in_all_interfaces(Symbol* name,
                                                       Symbol* signature,
                                                       DefaultsLookupMode defaults_mode) const {
  Array<Klass*>* all_ifs = transitive_interfaces();
  int num_ifs = all_ifs->length();
  InstanceKlass *ik = NULL;
  for (int i = 0; i < num_ifs; i++) {
    ik = InstanceKlass::cast(all_ifs->at(i));
    Method* m = ik->lookup_method(name, signature);
    if (m != NULL && m->is_public() && !m->is_static() &&
        ((defaults_mode != skip_defaults) || !m->is_default_method())) {
      return m;
    }
  }
  return NULL;
}

/* jni_id_for_impl for jfieldIds only */
JNIid* InstanceKlass::jni_id_for_impl(int offset) {
  MutexLocker ml(JfieldIdCreation_lock);
  // Retry lookup after we got the lock
  JNIid* probe = jni_ids() == NULL ? NULL : jni_ids()->find(offset);
  if (probe == NULL) {
    // Slow case, allocate new static field identifier
    probe = new JNIid(this, offset, jni_ids());
    set_jni_ids(probe);
  }
  return probe;
}


/* jni_id_for for jfieldIds only */
JNIid* InstanceKlass::jni_id_for(int offset) {
  JNIid* probe = jni_ids() == NULL ? NULL : jni_ids()->find(offset);
  if (probe == NULL) {
    probe = jni_id_for_impl(offset);
  }
  return probe;
}

u2 InstanceKlass::enclosing_method_data(int offset) const {
  const Array<jushort>* const inner_class_list = inner_classes();
  if (inner_class_list == NULL) {
    return 0;
  }
  const int length = inner_class_list->length();
  if (length % inner_class_next_offset == 0) {
    return 0;
  }
  const int index = length - enclosing_method_attribute_size;
  assert(offset < enclosing_method_attribute_size, "invalid offset");
  return inner_class_list->at(index + offset);
}

void InstanceKlass::set_enclosing_method_indices(u2 class_index,
                                                 u2 method_index) {
  Array<jushort>* inner_class_list = inner_classes();
  assert (inner_class_list != NULL, "_inner_classes list is not set up");
  int length = inner_class_list->length();
  if (length % inner_class_next_offset == enclosing_method_attribute_size) {
    int index = length - enclosing_method_attribute_size;
    inner_class_list->at_put(
      index + enclosing_method_class_index_offset, class_index);
    inner_class_list->at_put(
      index + enclosing_method_method_index_offset, method_index);
  }
}

// Lookup or create a jmethodID.
// This code is called by the VMThread and JavaThreads so the
// locking has to be done very carefully to avoid deadlocks
// and/or other cache consistency problems.
//
jmethodID InstanceKlass::get_jmethod_id(const methodHandle& method_h) {
  size_t idnum = (size_t)method_h->method_idnum();
  jmethodID* jmeths = methods_jmethod_ids_acquire();
  size_t length = 0;
  jmethodID id = NULL;

  // We use a double-check locking idiom here because this cache is
  // performance sensitive. In the normal system, this cache only
  // transitions from NULL to non-NULL which is safe because we use
  // release_set_methods_jmethod_ids() to advertise the new cache.
  // A partially constructed cache should never be seen by a racing
  // thread. We also use release_store() to save a new jmethodID
  // in the cache so a partially constructed jmethodID should never be
  // seen either. Cache reads of existing jmethodIDs proceed without a
  // lock, but cache writes of a new jmethodID requires uniqueness and
  // creation of the cache itself requires no leaks so a lock is
  // generally acquired in those two cases.
  //
  // If the RedefineClasses() API has been used, then this cache can
  // grow and we'll have transitions from non-NULL to bigger non-NULL.
  // Cache creation requires no leaks and we require safety between all
  // cache accesses and freeing of the old cache so a lock is generally
  // acquired when the RedefineClasses() API has been used.

  if (jmeths != NULL) {
    // the cache already exists
    if (!idnum_can_increment()) {
      // the cache can't grow so we can just get the current values
      get_jmethod_id_length_value(jmeths, idnum, &length, &id);
    } else {
      // cache can grow so we have to be more careful
      if (Threads::number_of_threads() == 0 ||
          SafepointSynchronize::is_at_safepoint()) {
        // we're single threaded or at a safepoint - no locking needed
        get_jmethod_id_length_value(jmeths, idnum, &length, &id);
      } else {
        MutexLocker ml(JmethodIdCreation_lock);
        get_jmethod_id_length_value(jmeths, idnum, &length, &id);
      }
    }
  }
  // implied else:
  // we need to allocate a cache so default length and id values are good

  if (jmeths == NULL ||   // no cache yet
      length <= idnum ||  // cache is too short
      id == NULL) {       // cache doesn't contain entry

    // This function can be called by the VMThread so we have to do all
    // things that might block on a safepoint before grabbing the lock.
    // Otherwise, we can deadlock with the VMThread or have a cache
    // consistency issue. These vars keep track of what we might have
    // to free after the lock is dropped.
    jmethodID  to_dealloc_id     = NULL;
    jmethodID* to_dealloc_jmeths = NULL;

    // may not allocate new_jmeths or use it if we allocate it
    jmethodID* new_jmeths = NULL;
    if (length <= idnum) {
      // allocate a new cache that might be used
      size_t size = MAX2(idnum+1, (size_t)idnum_allocated_count());
      new_jmeths = NEW_C_HEAP_ARRAY(jmethodID, size+1, mtClass);
      memset(new_jmeths, 0, (size+1)*sizeof(jmethodID));
      // cache size is stored in element[0], other elements offset by one
      new_jmeths[0] = (jmethodID)size;
    }

    // allocate a new jmethodID that might be used
    jmethodID new_id = NULL;
    if (method_h->is_old() && !method_h->is_obsolete()) {
      // The method passed in is old (but not obsolete), we need to use the current version
      Method* current_method = method_with_idnum((int)idnum);
      assert(current_method != NULL, "old and but not obsolete, so should exist");
      new_id = Method::make_jmethod_id(class_loader_data(), current_method);
    } else {
      // It is the current version of the method or an obsolete method,
      // use the version passed in
      new_id = Method::make_jmethod_id(class_loader_data(), method_h());
    }

    if (Threads::number_of_threads() == 0 ||
        SafepointSynchronize::is_at_safepoint()) {
      // we're single threaded or at a safepoint - no locking needed
      id = get_jmethod_id_fetch_or_update(idnum, new_id, new_jmeths,
                                          &to_dealloc_id, &to_dealloc_jmeths);
    } else {
      MutexLocker ml(JmethodIdCreation_lock);
      id = get_jmethod_id_fetch_or_update(idnum, new_id, new_jmeths,
                                          &to_dealloc_id, &to_dealloc_jmeths);
    }

    // The lock has been dropped so we can free resources.
    // Free up either the old cache or the new cache if we allocated one.
    if (to_dealloc_jmeths != NULL) {
      FreeHeap(to_dealloc_jmeths);
    }
    // free up the new ID since it wasn't needed
    if (to_dealloc_id != NULL) {
      Method::destroy_jmethod_id(class_loader_data(), to_dealloc_id);
    }
  }
  return id;
}

// Figure out how many jmethodIDs haven't been allocated, and make
// sure space for them is pre-allocated.  This makes getting all
// method ids much, much faster with classes with more than 8
// methods, and has a *substantial* effect on performance with jvmti
// code that loads all jmethodIDs for all classes.
void InstanceKlass::ensure_space_for_methodids(int start_offset) {
  int new_jmeths = 0;
  int length = methods()->length();
  for (int index = start_offset; index < length; index++) {
    Method* m = methods()->at(index);
    jmethodID id = m->find_jmethod_id_or_null();
    if (id == NULL) {
      new_jmeths++;
    }
  }
  if (new_jmeths != 0) {
    Method::ensure_jmethod_ids(class_loader_data(), new_jmeths);
  }
}

// Common code to fetch the jmethodID from the cache or update the
// cache with the new jmethodID. This function should never do anything
// that causes the caller to go to a safepoint or we can deadlock with
// the VMThread or have cache consistency issues.
//
jmethodID InstanceKlass::get_jmethod_id_fetch_or_update(
            size_t idnum, jmethodID new_id,
            jmethodID* new_jmeths, jmethodID* to_dealloc_id_p,
            jmethodID** to_dealloc_jmeths_p) {
  assert(new_id != NULL, "sanity check");
  assert(to_dealloc_id_p != NULL, "sanity check");
  assert(to_dealloc_jmeths_p != NULL, "sanity check");
  assert(Threads::number_of_threads() == 0 ||
         SafepointSynchronize::is_at_safepoint() ||
         JmethodIdCreation_lock->owned_by_self(), "sanity check");

  // reacquire the cache - we are locked, single threaded or at a safepoint
  jmethodID* jmeths = methods_jmethod_ids_acquire();
  jmethodID  id     = NULL;
  size_t     length = 0;

  if (jmeths == NULL ||                         // no cache yet
      (length = (size_t)jmeths[0]) <= idnum) {  // cache is too short
    if (jmeths != NULL) {
      // copy any existing entries from the old cache
      for (size_t index = 0; index < length; index++) {
        new_jmeths[index+1] = jmeths[index+1];
      }
      *to_dealloc_jmeths_p = jmeths;  // save old cache for later delete
    }
    release_set_methods_jmethod_ids(jmeths = new_jmeths);
  } else {
    // fetch jmethodID (if any) from the existing cache
    id = jmeths[idnum+1];
    *to_dealloc_jmeths_p = new_jmeths;  // save new cache for later delete
  }
  if (id == NULL) {
    // No matching jmethodID in the existing cache or we have a new
    // cache or we just grew the cache. This cache write is done here
    // by the first thread to win the foot race because a jmethodID
    // needs to be unique once it is generally available.
    id = new_id;

    // The jmethodID cache can be read while unlocked so we have to
    // make sure the new jmethodID is complete before installing it
    // in the cache.
    OrderAccess::release_store(&jmeths[idnum+1], id);
  } else {
    *to_dealloc_id_p = new_id; // save new id for later delete
  }
  return id;
}


// Common code to get the jmethodID cache length and the jmethodID
// value at index idnum if there is one.
//
void InstanceKlass::get_jmethod_id_length_value(jmethodID* cache,
       size_t idnum, size_t *length_p, jmethodID* id_p) {
  assert(cache != NULL, "sanity check");
  assert(length_p != NULL, "sanity check");
  assert(id_p != NULL, "sanity check");

  // cache size is stored in element[0], other elements offset by one
  *length_p = (size_t)cache[0];
  if (*length_p <= idnum) {  // cache is too short
    *id_p = NULL;
  } else {
    *id_p = cache[idnum+1];  // fetch jmethodID (if any)
  }
}


// Lookup a jmethodID, NULL if not found.  Do no blocking, no allocations, no handles
jmethodID InstanceKlass::jmethod_id_or_null(Method* method) {
  size_t idnum = (size_t)method->method_idnum();
  jmethodID* jmeths = methods_jmethod_ids_acquire();
  size_t length;                                // length assigned as debugging crumb
  jmethodID id = NULL;
  if (jmeths != NULL &&                         // If there is a cache
      (length = (size_t)jmeths[0]) > idnum) {   // and if it is long enough,
    id = jmeths[idnum+1];                       // Look up the id (may be NULL)
  }
  return id;
}

inline DependencyContext InstanceKlass::dependencies() {
  DependencyContext dep_context(&_dep_context);
  return dep_context;
}

int InstanceKlass::mark_dependent_nmethods(KlassDepChange& changes) {
  return dependencies().mark_dependent_nmethods(changes);
}

void InstanceKlass::add_dependent_nmethod(nmethod* nm) {
  dependencies().add_dependent_nmethod(nm);
}

// DCEVM - update jmethod ids
bool InstanceKlass::update_jmethod_id(Method* method, jmethodID newMethodID) {
  size_t idnum = (size_t)method->method_idnum();
  jmethodID* jmeths = methods_jmethod_ids_acquire();
  size_t length;                                // length assigned as debugging crumb
  jmethodID id = NULL;
  if (jmeths != NULL &&                         // If there is a cache
      (length = (size_t)jmeths[0]) > idnum) {   // and if it is long enough,
    jmeths[idnum+1] = newMethodID;              // Set method id (may be NULL)
    return true;
  }
  return false;
}

void InstanceKlass::remove_dependent_nmethod(nmethod* nm, bool delete_immediately) {
  dependencies().remove_dependent_nmethod(nm, delete_immediately);
  // FIXME: (DCEVM) Hack as dependencies get wrong version of Klass*
//  if (this->old_version() != NULL) {
//    InstanceKlass::cast(this->old_version())->remove_dependent_nmethod(nm, true);
//    return;
//  }
}

#ifndef PRODUCT
void InstanceKlass::print_dependent_nmethods(bool verbose) {
  dependencies().print_dependent_nmethods(verbose);
}

bool InstanceKlass::is_dependent_nmethod(nmethod* nm) {
  return dependencies().is_dependent_nmethod(nm);
}
#endif //PRODUCT

void InstanceKlass::clean_weak_instanceklass_links() {
  clean_implementors_list();
  clean_method_data();

  // Since GC iterates InstanceKlasses sequentially, it is safe to remove stale entries here.
  DependencyContext dep_context(&_dep_context);
  dep_context.expunge_stale_entries();
}

void InstanceKlass::clean_implementors_list() {
  assert(is_loader_alive(), "this klass should be live");
  if (is_interface()) {
    if (ClassUnloading) {
      Klass* impl = implementor();
      if (impl != NULL) {
        if (!impl->is_loader_alive()) {
          // remove this guy
          Klass** klass = adr_implementor();
          assert(klass != NULL, "null klass");
          if (klass != NULL) {
            *klass = NULL;
          }
        }
      }
    }
  }
}

void InstanceKlass::clean_method_data() {
  for (int m = 0; m < methods()->length(); m++) {
    MethodData* mdo = methods()->at(m)->method_data();
    if (mdo != NULL) {
      mdo->clean_method_data(/*always_clean*/false);
    }
  }
}

bool InstanceKlass::supers_have_passed_fingerprint_checks() {
  if (java_super() != NULL && !java_super()->has_passed_fingerprint_check()) {
    ResourceMark rm;
    log_trace(class, fingerprint)("%s : super %s not fingerprinted", external_name(), java_super()->external_name());
    return false;
  }

  Array<Klass*>* local_interfaces = this->local_interfaces();
  if (local_interfaces != NULL) {
    int length = local_interfaces->length();
    for (int i = 0; i < length; i++) {
      InstanceKlass* intf = InstanceKlass::cast(local_interfaces->at(i));
      if (!intf->has_passed_fingerprint_check()) {
        ResourceMark rm;
        log_trace(class, fingerprint)("%s : interface %s not fingerprinted", external_name(), intf->external_name());
        return false;
      }
    }
  }

  return true;
}

bool InstanceKlass::should_store_fingerprint(bool is_anonymous) {
#if INCLUDE_AOT
  // We store the fingerprint into the InstanceKlass only in the following 2 cases:
  if (CalculateClassFingerprint) {
    // (1) We are running AOT to generate a shared library.
    return true;
  }
  if (DumpSharedSpaces) {
    // (2) We are running -Xshare:dump to create a shared archive
    return true;
  }
  if (UseAOT && is_anonymous) {
    // (3) We are using AOT code from a shared library and see an anonymous class
    return true;
  }
#endif

  // In all other cases we might set the _misc_has_passed_fingerprint_check bit,
  // but do not store the 64-bit fingerprint to save space.
  return false;
}

bool InstanceKlass::has_stored_fingerprint() const {
#if INCLUDE_AOT
  return should_store_fingerprint() || is_shared();
#else
  return false;
#endif
}

uint64_t InstanceKlass::get_stored_fingerprint() const {
  address adr = adr_fingerprint();
  if (adr != NULL) {
    return (uint64_t)Bytes::get_native_u8(adr); // adr may not be 64-bit aligned
  }
  return 0;
}

void InstanceKlass::store_fingerprint(uint64_t fingerprint) {
  address adr = adr_fingerprint();
  if (adr != NULL) {
    Bytes::put_native_u8(adr, (u8)fingerprint); // adr may not be 64-bit aligned

    ResourceMark rm;
    log_trace(class, fingerprint)("stored as " PTR64_FORMAT " for class %s", fingerprint, external_name());
  }
}

void InstanceKlass::metaspace_pointers_do(MetaspaceClosure* it) {
  Klass::metaspace_pointers_do(it);

  if (log_is_enabled(Trace, cds)) {
    ResourceMark rm;
    log_trace(cds)("Iter(InstanceKlass): %p (%s)", this, external_name());
  }

  it->push(&_annotations);
  it->push((Klass**)&_array_klasses);
  it->push(&_constants);
  it->push(&_inner_classes);
  it->push(&_array_name);
#if INCLUDE_JVMTI
  it->push(&_previous_versions);
#endif
  it->push(&_methods);
  it->push(&_default_methods);
  it->push(&_local_interfaces);
  it->push(&_transitive_interfaces);
  it->push(&_method_ordering);
  it->push(&_default_vtable_indices);
  it->push(&_fields);

  if (itable_length() > 0) {
    itableOffsetEntry* ioe = (itableOffsetEntry*)start_of_itable();
    int method_table_offset_in_words = ioe->offset()/wordSize;
    int nof_interfaces = (method_table_offset_in_words - itable_offset_in_words())
                         / itableOffsetEntry::size();

    for (int i = 0; i < nof_interfaces; i ++, ioe ++) {
      if (ioe->interface_klass() != NULL) {
        it->push(ioe->interface_klass_addr());
        itableMethodEntry* ime = ioe->first_method_entry(this);
        int n = klassItable::method_count_for_interface(ioe->interface_klass());
        for (int index = 0; index < n; index ++) {
          it->push(ime[index].method_addr());
        }
      }
    }
  }

  it->push(&_nest_members);
}

void InstanceKlass::remove_unshareable_info() {
  Klass::remove_unshareable_info();

  if (is_in_error_state()) {
    // Classes are attempted to link during dumping and may fail,
    // but these classes are still in the dictionary and class list in CLD.
    // Check in_error state first because in_error is > linked state, so
    // is_linked() is true.
    // If there's a linking error, there is nothing else to remove.
    return;
  }

  // Reset to the 'allocated' state to prevent any premature accessing to
  // a shared class at runtime while the class is still being loaded and
  // restored. A class' init_state is set to 'loaded' at runtime when it's
  // being added to class hierarchy (see SystemDictionary:::add_to_hierarchy()).
  _init_state = allocated;

  {
    MutexLocker ml(Compile_lock);
    init_implementor();
  }

  constants()->remove_unshareable_info();

  for (int i = 0; i < methods()->length(); i++) {
    Method* m = methods()->at(i);
    m->remove_unshareable_info();
  }

  // do array classes also.
  if (array_klasses() != NULL) {
    array_klasses()->remove_unshareable_info();
  }

  // These are not allocated from metaspace, but they should should all be empty
  // during dump time, so we don't need to worry about them in InstanceKlass::iterate().
  guarantee(_source_debug_extension == NULL, "must be");
  guarantee(_dep_context == DependencyContext::EMPTY, "must be");
  guarantee(_osr_nmethods_head == NULL, "must be");

#if INCLUDE_JVMTI
  guarantee(_breakpoints == NULL, "must be");
  guarantee(_previous_versions == NULL, "must be");
#endif

  _init_thread = NULL;
  _methods_jmethod_ids = NULL;
  _jni_ids = NULL;
  _oop_map_cache = NULL;
  // clear _nest_host to ensure re-load at runtime
  _nest_host = NULL;
}

void InstanceKlass::remove_java_mirror() {
  Klass::remove_java_mirror();

  // do array classes also.
  if (array_klasses() != NULL) {
    array_klasses()->remove_java_mirror();
  }
}

void InstanceKlass::restore_unshareable_info(ClassLoaderData* loader_data, Handle protection_domain, TRAPS) {
  // SystemDictionary::add_to_hierarchy() sets the init_state to loaded
  // before the InstanceKlass is added to the SystemDictionary. Make
  // sure the current state is <loaded.
  assert(!is_loaded(), "invalid init state");
  set_package(loader_data, CHECK);
  Klass::restore_unshareable_info(loader_data, protection_domain, CHECK);

  Array<Method*>* methods = this->methods();
  int num_methods = methods->length();
  for (int index2 = 0; index2 < num_methods; ++index2) {
    methodHandle m(THREAD, methods->at(index2));
    m->restore_unshareable_info(CHECK);
  }
  if (JvmtiExport::has_redefined_a_class()) {
    // Reinitialize vtable because RedefineClasses may have changed some
    // entries in this vtable for super classes so the CDS vtable might
    // point to old or obsolete entries.  RedefineClasses doesn't fix up
    // vtables in the shared system dictionary, only the main one.
    // It also redefines the itable too so fix that too.
    ResourceMark rm(THREAD);
    vtable().initialize_vtable(false, CHECK);
    itable().initialize_itable(false, CHECK);
  }

  // restore constant pool resolved references
  constants()->restore_unshareable_info(CHECK);

  if (array_klasses() != NULL) {
    // Array classes have null protection domain.
    // --> see ArrayKlass::complete_create_array_klass()
    array_klasses()->restore_unshareable_info(ClassLoaderData::the_null_class_loader_data(), Handle(), CHECK);
  }
}

// returns true IFF is_in_error_state() has been changed as a result of this call.
bool InstanceKlass::check_sharing_error_state() {
  assert(DumpSharedSpaces, "should only be called during dumping");
  bool old_state = is_in_error_state();

  if (!is_in_error_state()) {
    bool bad = false;
    for (InstanceKlass* sup = java_super(); sup; sup = sup->java_super()) {
      if (sup->is_in_error_state()) {
        bad = true;
        break;
      }
    }
    if (!bad) {
      Array<Klass*>* interfaces = transitive_interfaces();
      for (int i = 0; i < interfaces->length(); i++) {
        Klass* iface = interfaces->at(i);
        if (InstanceKlass::cast(iface)->is_in_error_state()) {
          bad = true;
          break;
        }
      }
    }

    if (bad) {
      set_in_error_state();
    }
  }

  return (old_state != is_in_error_state());
}

#if INCLUDE_JVMTI
static void clear_all_breakpoints(Method* m) {
  m->clear_all_breakpoints();
}
#endif

void InstanceKlass::notify_unload_class(InstanceKlass* ik) {
  // notify the debugger
  if (JvmtiExport::should_post_class_unload()) {
    JvmtiExport::post_class_unload(ik);
  }

  // notify ClassLoadingService of class unload
  ClassLoadingService::notify_class_unloaded(ik);
}

void InstanceKlass::release_C_heap_structures(InstanceKlass* ik) {
  // Clean up C heap
  ik->release_C_heap_structures();
  ik->constants()->release_C_heap_structures();
}

void InstanceKlass::release_C_heap_structures() {
  // Can't release the constant pool here because the constant pool can be
  // deallocated separately from the InstanceKlass for default methods and
  // redefine classes.

  // Deallocate oop map cache
  if (_oop_map_cache != NULL) {
    delete _oop_map_cache;
    _oop_map_cache = NULL;
  }

  // Deallocate JNI identifiers for jfieldIDs
  JNIid::deallocate(jni_ids());
  set_jni_ids(NULL);

  jmethodID* jmeths = methods_jmethod_ids_acquire();
  if (jmeths != (jmethodID*)NULL) {
    release_set_methods_jmethod_ids(NULL);
    FreeHeap(jmeths);
  }

  // Release dependencies.
  // It is desirable to use DC::remove_all_dependents() here, but, unfortunately,
  // it is not safe (see JDK-8143408). The problem is that the klass dependency
  // context can contain live dependencies, since there's a race between nmethod &
  // klass unloading. If the klass is dead when nmethod unloading happens, relevant
  // dependencies aren't removed from the context associated with the class (see
  // nmethod::flush_dependencies). It ends up during klass unloading as seemingly
  // live dependencies pointing to unloaded nmethods and causes a crash in
  // DC::remove_all_dependents() when it touches unloaded nmethod.
  dependencies().wipe();

#if INCLUDE_JVMTI
  // Deallocate breakpoint records
  if (breakpoints() != 0x0) {
    methods_do(clear_all_breakpoints);
    assert(breakpoints() == 0x0, "should have cleared breakpoints");
  }

  // deallocate the cached class file
  if (_cached_class_file != NULL && !MetaspaceShared::is_in_shared_metaspace(_cached_class_file)) {
    os::free(_cached_class_file);
    _cached_class_file = NULL;
  }
#endif

  // Decrement symbol reference counts associated with the unloaded class.
  if (_name != NULL) _name->decrement_refcount();
  // unreference array name derived from this class name (arrays of an unloaded
  // class can't be referenced anymore).
  if (_array_name != NULL)  _array_name->decrement_refcount();
  if (_source_debug_extension != NULL) FREE_C_HEAP_ARRAY(char, _source_debug_extension);
}

void InstanceKlass::set_source_debug_extension(const char* array, int length) {
  if (array == NULL) {
    _source_debug_extension = NULL;
  } else {
    // Adding one to the attribute length in order to store a null terminator
    // character could cause an overflow because the attribute length is
    // already coded with an u4 in the classfile, but in practice, it's
    // unlikely to happen.
    assert((length+1) > length, "Overflow checking");
    char* sde = NEW_C_HEAP_ARRAY(char, (length + 1), mtClass);
    for (int i = 0; i < length; i++) {
      sde[i] = array[i];
    }
    sde[length] = '\0';
    _source_debug_extension = sde;
  }
}

const char* InstanceKlass::signature_name() const {
  int hash_len = 0;
  char hash_buf[40];

  // If this is an anonymous class, append a hash to make the name unique
  if (is_anonymous()) {
    intptr_t hash = (java_mirror() != NULL) ? java_mirror()->identity_hash() : 0;
    jio_snprintf(hash_buf, sizeof(hash_buf), "/" UINTX_FORMAT, (uintx)hash);
    hash_len = (int)strlen(hash_buf);
  }

  // Get the internal name as a c string
  const char* src = (const char*) (name()->as_C_string());
  const int src_length = (int)strlen(src);

  char* dest = NEW_RESOURCE_ARRAY(char, src_length + hash_len + 3);

  // Add L as type indicator
  int dest_index = 0;
  dest[dest_index++] = 'L';

  // Add the actual class name
  for (int src_index = 0; src_index < src_length; ) {
    dest[dest_index++] = src[src_index++];
  }

  // If we have a hash, append it
  for (int hash_index = 0; hash_index < hash_len; ) {
    dest[dest_index++] = hash_buf[hash_index++];
  }

  // Add the semicolon and the NULL
  dest[dest_index++] = ';';
  dest[dest_index] = '\0';
  return dest;
}

// Used to obtain the package name from a fully qualified class name.
Symbol* InstanceKlass::package_from_name(const Symbol* name, TRAPS) {
  if (name == NULL) {
    return NULL;
  } else {
    if (name->utf8_length() <= 0) {
      return NULL;
    }
    ResourceMark rm;
    const char* package_name = ClassLoader::package_from_name((const char*) name->as_C_string());
    if (package_name == NULL) {
      return NULL;
    }
    Symbol* pkg_name = SymbolTable::new_symbol(package_name, THREAD);
    return pkg_name;
  }
}

ModuleEntry* InstanceKlass::module() const {
  if (!in_unnamed_package()) {
    return _package_entry->module();
  }
  const Klass* host = host_klass();
  if (host == NULL) {
    return class_loader_data()->unnamed_module();
  }
  return host->class_loader_data()->unnamed_module();
}

void InstanceKlass::set_package(ClassLoaderData* loader_data, TRAPS) {

  // ensure java/ packages only loaded by boot or platform builtin loaders
  check_prohibited_package(name(), loader_data, CHECK);

  TempNewSymbol pkg_name = package_from_name(name(), CHECK);

  if (pkg_name != NULL && loader_data != NULL) {

    // Find in class loader's package entry table.
    _package_entry = loader_data->packages()->lookup_only(pkg_name);

    // If the package name is not found in the loader's package
    // entry table, it is an indication that the package has not
    // been defined. Consider it defined within the unnamed module.
    if (_package_entry == NULL) {
      ResourceMark rm;

      if (!ModuleEntryTable::javabase_defined()) {
        // Before java.base is defined during bootstrapping, define all packages in
        // the java.base module.  If a non-java.base package is erroneously placed
        // in the java.base module it will be caught later when java.base
        // is defined by ModuleEntryTable::verify_javabase_packages check.
        assert(ModuleEntryTable::javabase_moduleEntry() != NULL, JAVA_BASE_NAME " module is NULL");
        _package_entry = loader_data->packages()->lookup(pkg_name, ModuleEntryTable::javabase_moduleEntry());
      } else {
        assert(loader_data->unnamed_module() != NULL, "unnamed module is NULL");
        _package_entry = loader_data->packages()->lookup(pkg_name,
                                                         loader_data->unnamed_module());
      }

      // A package should have been successfully created
      assert(_package_entry != NULL, "Package entry for class %s not found, loader %s",
             name()->as_C_string(), loader_data->loader_name_and_id());
    }

    if (log_is_enabled(Debug, module)) {
      ResourceMark rm;
      ModuleEntry* m = _package_entry->module();
      log_trace(module)("Setting package: class: %s, package: %s, loader: %s, module: %s",
                        external_name(),
                        pkg_name->as_C_string(),
                        loader_data->loader_name_and_id(),
                        (m->is_named() ? m->name()->as_C_string() : UNNAMED_MODULE));
    }
  } else {
    ResourceMark rm;
    log_trace(module)("Setting package: class: %s, package: unnamed, loader: %s, module: %s",
                      external_name(),
                      (loader_data != NULL) ? loader_data->loader_name_and_id() : "NULL",
                      UNNAMED_MODULE);
  }
}


// different versions of is_same_class_package

bool InstanceKlass::is_same_class_package(const Klass* class2) const {
  oop classloader1 = this->class_loader();
  PackageEntry* classpkg1 = this->package();
  if (class2->is_objArray_klass()) {
    class2 = ObjArrayKlass::cast(class2)->bottom_klass();
  }

  oop classloader2;
  PackageEntry* classpkg2;
  if (class2->is_instance_klass()) {
    classloader2 = class2->class_loader();
    classpkg2 = class2->package();
  } else {
    assert(class2->is_typeArray_klass(), "should be type array");
    classloader2 = NULL;
    classpkg2 = NULL;
  }

  // Same package is determined by comparing class loader
  // and package entries. Both must be the same. This rule
  // applies even to classes that are defined in the unnamed
  // package, they still must have the same class loader.
  if (oopDesc::equals(classloader1, classloader2) && (classpkg1 == classpkg2)) {
    return true;
  }

  return false;
}

// return true if this class and other_class are in the same package. Classloader
// and classname information is enough to determine a class's package
bool InstanceKlass::is_same_class_package(oop other_class_loader,
                                          const Symbol* other_class_name) const {
  if (!oopDesc::equals(class_loader(), other_class_loader)) {
    return false;
  }
  if (name()->fast_compare(other_class_name) == 0) {
     return true;
  }

  {
    ResourceMark rm;

    bool bad_class_name = false;
    const char* other_pkg =
      ClassLoader::package_from_name((const char*) other_class_name->as_C_string(), &bad_class_name);
    if (bad_class_name) {
      return false;
    }
    // Check that package_from_name() returns NULL, not "", if there is no package.
    assert(other_pkg == NULL || strlen(other_pkg) > 0, "package name is empty string");

    const Symbol* const this_package_name =
      this->package() != NULL ? this->package()->name() : NULL;

    if (this_package_name == NULL || other_pkg == NULL) {
      // One of the two doesn't have a package.  Only return true if the other
      // one also doesn't have a package.
      return (const char*)this_package_name == other_pkg;
    }

    // Check if package is identical
    return this_package_name->equals(other_pkg);
  }
}

// Returns true iff super_method can be overridden by a method in targetclassname
// See JLS 3rd edition 8.4.6.1
// Assumes name-signature match
// "this" is InstanceKlass of super_method which must exist
// note that the InstanceKlass of the method in the targetclassname has not always been created yet
bool InstanceKlass::is_override(const methodHandle& super_method, Handle targetclassloader, Symbol* targetclassname, TRAPS) {
   // Private methods can not be overridden
   if (super_method->is_private()) {
     return false;
   }
   // If super method is accessible, then override
   if ((super_method->is_protected()) ||
       (super_method->is_public())) {
     return true;
   }
   // Package-private methods are not inherited outside of package
   assert(super_method->is_package_private(), "must be package private");
   return(is_same_class_package(targetclassloader(), targetclassname));
}

// Only boot and platform class loaders can define classes in "java/" packages.
void InstanceKlass::check_prohibited_package(Symbol* class_name,
                                             ClassLoaderData* loader_data,
                                             TRAPS) {
  if (!loader_data->is_boot_class_loader_data() &&
      !loader_data->is_platform_class_loader_data() &&
      class_name != NULL) {
    ResourceMark rm(THREAD);
    char* name = class_name->as_C_string();
    if (strncmp(name, JAVAPKG, JAVAPKG_LEN) == 0 && name[JAVAPKG_LEN] == '/') {
      TempNewSymbol pkg_name = InstanceKlass::package_from_name(class_name, CHECK);
      assert(pkg_name != NULL, "Error in parsing package name starting with 'java/'");
      name = pkg_name->as_C_string();
      const char* class_loader_name = loader_data->loader_name_and_id();
      StringUtils::replace_no_expand(name, "/", ".");
      const char* msg_text1 = "Class loader (instance of): ";
      const char* msg_text2 = " tried to load prohibited package name: ";
      size_t len = strlen(msg_text1) + strlen(class_loader_name) + strlen(msg_text2) + strlen(name) + 1;
      char* message = NEW_RESOURCE_ARRAY_IN_THREAD(THREAD, char, len);
      jio_snprintf(message, len, "%s%s%s%s", msg_text1, class_loader_name, msg_text2, name);
      THROW_MSG(vmSymbols::java_lang_SecurityException(), message);
    }
  }
  return;
}

// tell if two classes have the same enclosing class (at package level)
bool InstanceKlass::is_same_package_member(const Klass* class2, TRAPS) const {
  if (class2 == this) return true;
  if (!class2->is_instance_klass())  return false;

  // must be in same package before we try anything else
  if (!is_same_class_package(class2))
    return false;

  // As long as there is an outer_this.getEnclosingClass,
  // shift the search outward.
  const InstanceKlass* outer_this = this;
  for (;;) {
    // As we walk along, look for equalities between outer_this and class2.
    // Eventually, the walks will terminate as outer_this stops
    // at the top-level class around the original class.
    bool ignore_inner_is_member;
    const Klass* next = outer_this->compute_enclosing_class(&ignore_inner_is_member,
                                                            CHECK_false);
    if (next == NULL)  break;
    if (next == class2)  return true;
    outer_this = InstanceKlass::cast(next);
  }

  // Now do the same for class2.
  const InstanceKlass* outer2 = InstanceKlass::cast(class2);
  for (;;) {
    bool ignore_inner_is_member;
    Klass* next = outer2->compute_enclosing_class(&ignore_inner_is_member,
                                                    CHECK_false);
    if (next == NULL)  break;
    // Might as well check the new outer against all available values.
    if (next == this)  return true;
    if (next == outer_this)  return true;
    outer2 = InstanceKlass::cast(next);
  }

  // If by this point we have not found an equality between the
  // two classes, we know they are in separate package members.
  return false;
}

bool InstanceKlass::find_inner_classes_attr(int* ooff, int* noff, TRAPS) const {
  constantPoolHandle i_cp(THREAD, constants());
  for (InnerClassesIterator iter(this); !iter.done(); iter.next()) {
    int ioff = iter.inner_class_info_index();
    if (ioff != 0) {
      // Check to see if the name matches the class we're looking for
      // before attempting to find the class.
      if (i_cp->klass_name_at_matches(this, ioff)) {
        Klass* inner_klass = i_cp->klass_at(ioff, CHECK_false);
        if (this == inner_klass) {
          *ooff = iter.outer_class_info_index();
          *noff = iter.inner_name_index();
          return true;
        }
      }
    }
  }
  return false;
}

InstanceKlass* InstanceKlass::compute_enclosing_class(bool* inner_is_member, TRAPS) const {
  InstanceKlass* outer_klass = NULL;
  *inner_is_member = false;
  int ooff = 0, noff = 0;
  bool has_inner_classes_attr = find_inner_classes_attr(&ooff, &noff, THREAD);
  if (has_inner_classes_attr) {
    constantPoolHandle i_cp(THREAD, constants());
    if (ooff != 0) {
      Klass* ok = i_cp->klass_at(ooff, CHECK_NULL);
      outer_klass = InstanceKlass::cast(ok);
      *inner_is_member = true;
    }
    if (NULL == outer_klass) {
      // It may be anonymous; try for that.
      int encl_method_class_idx = enclosing_method_class_index();
      if (encl_method_class_idx != 0) {
        Klass* ok = i_cp->klass_at(encl_method_class_idx, CHECK_NULL);
        outer_klass = InstanceKlass::cast(ok);
        *inner_is_member = false;
      }
    }
  }

  // If no inner class attribute found for this class.
  if (NULL == outer_klass) return NULL;

  // Throws an exception if outer klass has not declared k as an inner klass
  // We need evidence that each klass knows about the other, or else
  // the system could allow a spoof of an inner class to gain access rights.
  Reflection::check_for_inner_class(outer_klass, this, *inner_is_member, CHECK_NULL);
  return outer_klass;
}

jint InstanceKlass::compute_modifier_flags(TRAPS) const {
  jint access = access_flags().as_int();

  // But check if it happens to be member class.
  InnerClassesIterator iter(this);
  for (; !iter.done(); iter.next()) {
    int ioff = iter.inner_class_info_index();
    // Inner class attribute can be zero, skip it.
    // Strange but true:  JVM spec. allows null inner class refs.
    if (ioff == 0) continue;

    // only look at classes that are already loaded
    // since we are looking for the flags for our self.
    Symbol* inner_name = constants()->klass_name_at(ioff);
    if (name() == inner_name) {
      // This is really a member class.
      access = iter.inner_access_flags();
      break;
    }
  }
  // Remember to strip ACC_SUPER bit
  return (access & (~JVM_ACC_SUPER)) & JVM_ACC_WRITTEN_FLAGS;
}

jint InstanceKlass::jvmti_class_status() const {
  jint result = 0;

  if (is_linked()) {
    result |= JVMTI_CLASS_STATUS_VERIFIED | JVMTI_CLASS_STATUS_PREPARED;
  }

  if (is_initialized()) {
    assert(is_linked(), "Class status is not consistent");
    result |= JVMTI_CLASS_STATUS_INITIALIZED;
  }
  if (is_in_error_state()) {
    result |= JVMTI_CLASS_STATUS_ERROR;
  }
  return result;
}

Method* InstanceKlass::method_at_itable(Klass* holder, int index, TRAPS) {
  itableOffsetEntry* ioe = (itableOffsetEntry*)start_of_itable();
  int method_table_offset_in_words = ioe->offset()/wordSize;
  int nof_interfaces = (method_table_offset_in_words - itable_offset_in_words())
                       / itableOffsetEntry::size();

  for (int cnt = 0 ; ; cnt ++, ioe ++) {
    // If the interface isn't implemented by the receiver class,
    // the VM should throw IncompatibleClassChangeError.
    if (cnt >= nof_interfaces) {
      ResourceMark rm(THREAD);
      stringStream ss;
      bool same_module = (module() == holder->module());
      ss.print("Receiver class %s does not implement "
               "the interface %s defining the method to be called "
               "(%s%s%s)",
               external_name(), holder->external_name(),
               (same_module) ? joint_in_module_of_loader(holder) : class_in_module_of_loader(),
               (same_module) ? "" : "; ",
               (same_module) ? "" : holder->class_in_module_of_loader());
      THROW_MSG_NULL(vmSymbols::java_lang_IncompatibleClassChangeError(), ss.as_string());
    }

    Klass* ik = ioe->interface_klass();
    if (ik == holder) break;
  }

  itableMethodEntry* ime = ioe->first_method_entry(this);
  Method* m = ime[index].method();
  if (m == NULL) {
    THROW_NULL(vmSymbols::java_lang_AbstractMethodError());
  }
  return m;
}


#if INCLUDE_JVMTI
// update default_methods for redefineclasses for methods that are
// not yet in the vtable due to concurrent subclass define and superinterface
// redefinition
// Note: those in the vtable, should have been updated via adjust_method_entries
void InstanceKlass::adjust_default_methods(InstanceKlass* holder, bool* trace_name_printed) {
  // search the default_methods for uses of either obsolete or EMCP methods
  if (default_methods() != NULL) {
    for (int index = 0; index < default_methods()->length(); index ++) {
      Method* old_method = default_methods()->at(index);
      if (old_method == NULL || old_method->method_holder() != holder || !old_method->is_old()) {
        continue; // skip uninteresting entries
      }
      assert(!old_method->is_deleted(), "default methods may not be deleted");

      Method* new_method = holder->method_with_idnum(old_method->orig_method_idnum());

      assert(new_method != NULL, "method_with_idnum() should not be NULL");
      assert(old_method != new_method, "sanity check");

      default_methods()->at_put(index, new_method);
      if (log_is_enabled(Info, redefine, class, update)) {
        ResourceMark rm;
        if (!(*trace_name_printed)) {
          log_info(redefine, class, update)
            ("adjust: klassname=%s default methods from name=%s",
             external_name(), old_method->method_holder()->external_name());
          *trace_name_printed = true;
        }
        log_debug(redefine, class, update, vtables)
          ("default method update: %s(%s) ",
           new_method->name()->as_C_string(), new_method->signature()->as_C_string());
      }
    }
  }
}
#endif // INCLUDE_JVMTI

// On-stack replacement stuff
void InstanceKlass::add_osr_nmethod(nmethod* n) {
#ifndef PRODUCT
  if (TieredCompilation) {
      nmethod * prev = lookup_osr_nmethod(n->method(), n->osr_entry_bci(), n->comp_level(), true);
      assert(prev == NULL || !prev->is_in_use(),
      "redundunt OSR recompilation detected. memory leak in CodeCache!");
  }
#endif
  // only one compilation can be active
  {
    // This is a short non-blocking critical region, so the no safepoint check is ok.
    MutexLockerEx ml(OsrList_lock, Mutex::_no_safepoint_check_flag);
    assert(n->is_osr_method(), "wrong kind of nmethod");
    n->set_osr_link(osr_nmethods_head());
    set_osr_nmethods_head(n);
    // Raise the highest osr level if necessary
    if (TieredCompilation) {
      Method* m = n->method();
      m->set_highest_osr_comp_level(MAX2(m->highest_osr_comp_level(), n->comp_level()));
    }
  }

  // Get rid of the osr methods for the same bci that have lower levels.
  if (TieredCompilation) {
    for (int l = CompLevel_limited_profile; l < n->comp_level(); l++) {
      nmethod *inv = lookup_osr_nmethod(n->method(), n->osr_entry_bci(), l, true);
      if (inv != NULL && inv->is_in_use()) {
        inv->make_not_entrant();
      }
    }
  }
}

// Remove osr nmethod from the list. Return true if found and removed.
bool InstanceKlass::remove_osr_nmethod(nmethod* n) {
  // This is a short non-blocking critical region, so the no safepoint check is ok.
  MutexLockerEx ml(OsrList_lock, Mutex::_no_safepoint_check_flag);
  assert(n->is_osr_method(), "wrong kind of nmethod");
  nmethod* last = NULL;
  nmethod* cur  = osr_nmethods_head();
  int max_level = CompLevel_none;  // Find the max comp level excluding n
  Method* m = n->method();
  // Search for match
  bool found = false;
  while(cur != NULL && cur != n) {
    if (TieredCompilation && m == cur->method()) {
      // Find max level before n
      max_level = MAX2(max_level, cur->comp_level());
    }
    last = cur;
    cur = cur->osr_link();
  }
  nmethod* next = NULL;
  if (cur == n) {
    found = true;
    next = cur->osr_link();
    if (last == NULL) {
      // Remove first element
      set_osr_nmethods_head(next);
    } else {
      last->set_osr_link(next);
    }
  }
  n->set_osr_link(NULL);
  if (TieredCompilation) {
    cur = next;
    while (cur != NULL) {
      // Find max level after n
      if (m == cur->method()) {
        max_level = MAX2(max_level, cur->comp_level());
      }
      cur = cur->osr_link();
    }
    m->set_highest_osr_comp_level(max_level);
  }
  return found;
}

int InstanceKlass::mark_osr_nmethods(const Method* m) {
  // This is a short non-blocking critical region, so the no safepoint check is ok.
  MutexLockerEx ml(OsrList_lock, Mutex::_no_safepoint_check_flag);
  nmethod* osr = osr_nmethods_head();
  int found = 0;
  while (osr != NULL) {
    assert(osr->is_osr_method(), "wrong kind of nmethod found in chain");
    if (osr->method() == m) {
      osr->mark_for_deoptimization();
      found++;
    }
    osr = osr->osr_link();
  }
  return found;
}

nmethod* InstanceKlass::lookup_osr_nmethod(const Method* m, int bci, int comp_level, bool match_level) const {
  // This is a short non-blocking critical region, so the no safepoint check is ok.
  MutexLockerEx ml(OsrList_lock, Mutex::_no_safepoint_check_flag);
  nmethod* osr = osr_nmethods_head();
  nmethod* best = NULL;
  while (osr != NULL) {
    assert(osr->is_osr_method(), "wrong kind of nmethod found in chain");
    // There can be a time when a c1 osr method exists but we are waiting
    // for a c2 version. When c2 completes its osr nmethod we will trash
    // the c1 version and only be able to find the c2 version. However
    // while we overflow in the c1 code at back branches we don't want to
    // try and switch to the same code as we are already running

    if (osr->method() == m &&
        (bci == InvocationEntryBci || osr->osr_entry_bci() == bci)) {
      if (match_level) {
        if (osr->comp_level() == comp_level) {
          // Found a match - return it.
          return osr;
        }
      } else {
        if (best == NULL || (osr->comp_level() > best->comp_level())) {
          if (osr->comp_level() == CompLevel_highest_tier) {
            // Found the best possible - return it.
            return osr;
          }
          best = osr;
        }
      }
    }
    osr = osr->osr_link();
  }

  assert(match_level == false || best == NULL, "shouldn't pick up anything if match_level is set");
  if (best != NULL && best->comp_level() >= comp_level) {
    return best;
  }
  return NULL;
}

// -----------------------------------------------------------------------------------------------------
// Printing

#ifndef PRODUCT

#define BULLET  " - "

static const char* state_names[] = {
  "allocated", "loaded", "linked", "being_initialized", "fully_initialized", "initialization_error"
};

static void print_vtable(intptr_t* start, int len, outputStream* st) {
  for (int i = 0; i < len; i++) {
    intptr_t e = start[i];
    st->print("%d : " INTPTR_FORMAT, i, e);
    if (MetaspaceObj::is_valid((Metadata*)e)) {
      st->print(" ");
      ((Metadata*)e)->print_value_on(st);
    }
    st->cr();
  }
}

static void print_vtable(vtableEntry* start, int len, outputStream* st) {
  return print_vtable(reinterpret_cast<intptr_t*>(start), len, st);
}

void InstanceKlass::print_on(outputStream* st) const {
  assert(is_klass(), "must be klass");
  Klass::print_on(st);

  st->print(BULLET"instance size:     %d", size_helper());                        st->cr();
  st->print(BULLET"klass size:        %d", size());                               st->cr();
  st->print(BULLET"access:            "); access_flags().print_on(st);            st->cr();
  st->print(BULLET"state:             "); st->print_cr("%s", state_names[_init_state]);
  st->print(BULLET"name:              "); name()->print_value_on(st);             st->cr();
  st->print(BULLET"super:             "); super()->print_value_on_maybe_null(st); st->cr();
  st->print(BULLET"sub:               ");
  Klass* sub = subklass();
  int n;
  for (n = 0; sub != NULL; n++, sub = sub->next_sibling()) {
    if (n < MaxSubklassPrintSize) {
      sub->print_value_on(st);
      st->print("   ");
    }
  }
  if (n >= MaxSubklassPrintSize) st->print("(" INTX_FORMAT " more klasses...)", n - MaxSubklassPrintSize);
  st->cr();

  if (is_interface()) {
    MutexLocker ml(Compile_lock);
    st->print_cr(BULLET"nof implementors:  %d", nof_implementors());
    if (nof_implementors() == 1) {
      st->print_cr(BULLET"implementor:    ");
      st->print("   ");
      implementor()->print_value_on(st);
      st->cr();
    }
  }

  st->print(BULLET"arrays:            "); array_klasses()->print_value_on_maybe_null(st); st->cr();
  st->print(BULLET"methods:           "); methods()->print_value_on(st);                  st->cr();
  if (Verbose || WizardMode) {
    Array<Method*>* method_array = methods();
    for (int i = 0; i < method_array->length(); i++) {
      st->print("%d : ", i); method_array->at(i)->print_value(); st->cr();
    }
  }
  st->print(BULLET"method ordering:   "); method_ordering()->print_value_on(st);      st->cr();
  st->print(BULLET"default_methods:   "); default_methods()->print_value_on(st);      st->cr();
  if (Verbose && default_methods() != NULL) {
    Array<Method*>* method_array = default_methods();
    for (int i = 0; i < method_array->length(); i++) {
      st->print("%d : ", i); method_array->at(i)->print_value(); st->cr();
    }
  }
  if (default_vtable_indices() != NULL) {
    st->print(BULLET"default vtable indices:   "); default_vtable_indices()->print_value_on(st);       st->cr();
  }
  st->print(BULLET"local interfaces:  "); local_interfaces()->print_value_on(st);      st->cr();
  st->print(BULLET"trans. interfaces: "); transitive_interfaces()->print_value_on(st); st->cr();
  st->print(BULLET"constants:         "); constants()->print_value_on(st);         st->cr();
  if (class_loader_data() != NULL) {
    st->print(BULLET"class loader data:  ");
    class_loader_data()->print_value_on(st);
    st->cr();
  }
  st->print(BULLET"host class:        "); host_klass()->print_value_on_maybe_null(st); st->cr();
  if (source_file_name() != NULL) {
    st->print(BULLET"source file:       ");
    source_file_name()->print_value_on(st);
    st->cr();
  }
  if (source_debug_extension() != NULL) {
    st->print(BULLET"source debug extension:       ");
    st->print("%s", source_debug_extension());
    st->cr();
  }
  st->print(BULLET"class annotations:       "); class_annotations()->print_value_on(st); st->cr();
  st->print(BULLET"class type annotations:  "); class_type_annotations()->print_value_on(st); st->cr();
  st->print(BULLET"field annotations:       "); fields_annotations()->print_value_on(st); st->cr();
  st->print(BULLET"field type annotations:  "); fields_type_annotations()->print_value_on(st); st->cr();
  {
    bool have_pv = false;
    // previous versions are linked together through the InstanceKlass
    for (InstanceKlass* pv_node = previous_versions();
         pv_node != NULL;
         pv_node = pv_node->previous_versions()) {
      if (!have_pv)
        st->print(BULLET"previous version:  ");
      have_pv = true;
      pv_node->constants()->print_value_on(st);
    }
    if (have_pv) st->cr();
  }

  if (generic_signature() != NULL) {
    st->print(BULLET"generic signature: ");
    generic_signature()->print_value_on(st);
    st->cr();
  }
  st->print(BULLET"inner classes:     "); inner_classes()->print_value_on(st);     st->cr();
  st->print(BULLET"nest members:     "); nest_members()->print_value_on(st);     st->cr();
  st->print(BULLET"java mirror:       "); java_mirror()->print_value_on(st);       st->cr();
  st->print(BULLET"vtable length      %d  (start addr: " INTPTR_FORMAT ")", vtable_length(), p2i(start_of_vtable())); st->cr();
  if (vtable_length() > 0 && (Verbose || WizardMode))  print_vtable(start_of_vtable(), vtable_length(), st);
  st->print(BULLET"itable length      %d (start addr: " INTPTR_FORMAT ")", itable_length(), p2i(start_of_itable())); st->cr();
  if (itable_length() > 0 && (Verbose || WizardMode))  print_vtable(start_of_itable(), itable_length(), st);
  st->print_cr(BULLET"---- static fields (%d words):", static_field_size());
  FieldPrinter print_static_field(st);
  ((InstanceKlass*)this)->do_local_static_fields(&print_static_field);
  st->print_cr(BULLET"---- non-static fields (%d words):", nonstatic_field_size());
  FieldPrinter print_nonstatic_field(st);
  InstanceKlass* ik = const_cast<InstanceKlass*>(this);
  ik->do_nonstatic_fields(&print_nonstatic_field);

  st->print(BULLET"non-static oop maps: ");
  OopMapBlock* map     = start_of_nonstatic_oop_maps();
  OopMapBlock* end_map = map + nonstatic_oop_map_count();
  while (map < end_map) {
    st->print("%d-%d ", map->offset(), map->offset() + heapOopSize*(map->count() - 1));
    map++;
  }
  st->cr();
}

#endif //PRODUCT

void InstanceKlass::print_value_on(outputStream* st) const {
  assert(is_klass(), "must be klass");
  if (Verbose || WizardMode)  access_flags().print_on(st);
  name()->print_value_on(st);
}

#ifndef PRODUCT

void FieldPrinter::do_field(fieldDescriptor* fd) {
  _st->print(BULLET);
   if (_obj == NULL) {
     fd->print_on(_st);
     _st->cr();
   } else {
     fd->print_on_for(_st, _obj);
     _st->cr();
   }
}


void InstanceKlass::oop_print_on(oop obj, outputStream* st) {
  Klass::oop_print_on(obj, st);

  if (this == SystemDictionary::String_klass()) {
    typeArrayOop value  = java_lang_String::value(obj);
    juint        length = java_lang_String::length(obj);
    if (value != NULL &&
        value->is_typeArray() &&
        length <= (juint) value->length()) {
      st->print(BULLET"string: ");
      java_lang_String::print(obj, st);
      st->cr();
      if (!WizardMode)  return;  // that is enough
    }
  }

  st->print_cr(BULLET"---- fields (total size %d words):", oop_size(obj));
  FieldPrinter print_field(st, obj);
  do_nonstatic_fields(&print_field);

  if (this == SystemDictionary::Class_klass()) {
    st->print(BULLET"signature: ");
    java_lang_Class::print_signature(obj, st);
    st->cr();
    Klass* mirrored_klass = java_lang_Class::as_Klass(obj);
    st->print(BULLET"fake entry for mirror: ");
    mirrored_klass->print_value_on_maybe_null(st);
    st->cr();
    Klass* array_klass = java_lang_Class::array_klass_acquire(obj);
    st->print(BULLET"fake entry for array: ");
    array_klass->print_value_on_maybe_null(st);
    st->cr();
    st->print_cr(BULLET"fake entry for oop_size: %d", java_lang_Class::oop_size(obj));
    st->print_cr(BULLET"fake entry for static_oop_field_count: %d", java_lang_Class::static_oop_field_count(obj));
    Klass* real_klass = java_lang_Class::as_Klass(obj);
    if (real_klass != NULL && real_klass->is_instance_klass()) {
      InstanceKlass::cast(real_klass)->do_local_static_fields(&print_field);
    }
  } else if (this == SystemDictionary::MethodType_klass()) {
    st->print(BULLET"signature: ");
    java_lang_invoke_MethodType::print_signature(obj, st);
    st->cr();
  }
}

#endif //PRODUCT

void InstanceKlass::oop_print_value_on(oop obj, outputStream* st) {
  st->print("a ");
  name()->print_value_on(st);
  obj->print_address_on(st);
  if (this == SystemDictionary::String_klass()
      && java_lang_String::value(obj) != NULL) {
    ResourceMark rm;
    int len = java_lang_String::length(obj);
    int plen = (len < 24 ? len : 12);
    char* str = java_lang_String::as_utf8_string(obj, 0, plen);
    st->print(" = \"%s\"", str);
    if (len > plen)
      st->print("...[%d]", len);
  } else if (this == SystemDictionary::Class_klass()) {
    Klass* k = java_lang_Class::as_Klass(obj);
    st->print(" = ");
    if (k != NULL) {
      k->print_value_on(st);
    } else {
      const char* tname = type2name(java_lang_Class::primitive_type(obj));
      st->print("%s", tname ? tname : "type?");
    }
  } else if (this == SystemDictionary::MethodType_klass()) {
    st->print(" = ");
    java_lang_invoke_MethodType::print_signature(obj, st);
  } else if (java_lang_boxing_object::is_instance(obj)) {
    st->print(" = ");
    java_lang_boxing_object::print(obj, st);
  } else if (this == SystemDictionary::LambdaForm_klass()) {
    oop vmentry = java_lang_invoke_LambdaForm::vmentry(obj);
    if (vmentry != NULL) {
      st->print(" => ");
      vmentry->print_value_on(st);
    }
  } else if (this == SystemDictionary::MemberName_klass()) {
    Metadata* vmtarget = java_lang_invoke_MemberName::vmtarget(obj);
    if (vmtarget != NULL) {
      st->print(" = ");
      vmtarget->print_value_on(st);
    } else {
      java_lang_invoke_MemberName::clazz(obj)->print_value_on(st);
      st->print(".");
      java_lang_invoke_MemberName::name(obj)->print_value_on(st);
    }
  }
}

const char* InstanceKlass::internal_name() const {
  return external_name();
}

void InstanceKlass::print_class_load_logging(ClassLoaderData* loader_data,
                                             const char* module_name,
                                             const ClassFileStream* cfs) const {
  if (!log_is_enabled(Info, class, load)) {
    return;
  }

  ResourceMark rm;
  LogMessage(class, load) msg;
  stringStream info_stream;

  // Name and class hierarchy info
  info_stream.print("%s", external_name());

  // Source
  if (cfs != NULL) {
    if (cfs->source() != NULL) {
      if (module_name != NULL) {
        if (ClassLoader::is_modules_image(cfs->source())) {
          info_stream.print(" source: jrt:/%s", module_name);
        } else {
          info_stream.print(" source: %s", cfs->source());
        }
      } else {
        info_stream.print(" source: %s", cfs->source());
      }
    } else if (loader_data == ClassLoaderData::the_null_class_loader_data()) {
      Thread* THREAD = Thread::current();
      Klass* caller =
            THREAD->is_Java_thread()
                ? ((JavaThread*)THREAD)->security_get_caller_class(1)
                : NULL;
      // caller can be NULL, for example, during a JVMTI VM_Init hook
      if (caller != NULL) {
        info_stream.print(" source: instance of %s", caller->external_name());
      } else {
        // source is unknown
      }
    } else {
      oop class_loader = loader_data->class_loader();
      info_stream.print(" source: %s", class_loader->klass()->external_name());
    }
  } else {
    info_stream.print(" source: shared objects file");
  }

  msg.info("%s", info_stream.as_string());

  if (log_is_enabled(Debug, class, load)) {
    stringStream debug_stream;

    // Class hierarchy info
    debug_stream.print(" klass: " INTPTR_FORMAT " super: " INTPTR_FORMAT,
                       p2i(this),  p2i(superklass()));

    // Interfaces
    if (local_interfaces() != NULL && local_interfaces()->length() > 0) {
      debug_stream.print(" interfaces:");
      int length = local_interfaces()->length();
      for (int i = 0; i < length; i++) {
        debug_stream.print(" " INTPTR_FORMAT,
                           p2i(InstanceKlass::cast(local_interfaces()->at(i))));
      }
    }

    // Class loader
    debug_stream.print(" loader: [");
    loader_data->print_value_on(&debug_stream);
    debug_stream.print("]");

    // Classfile checksum
    if (cfs) {
      debug_stream.print(" bytes: %d checksum: %08x",
                         cfs->length(),
                         ClassLoader::crc32(0, (const char*)cfs->buffer(),
                         cfs->length()));
    }

    msg.debug("%s", debug_stream.as_string());
  }
}

#if INCLUDE_SERVICES
// Size Statistics
void InstanceKlass::collect_statistics(KlassSizeStats *sz) const {
  Klass::collect_statistics(sz);

  sz->_inst_size  = wordSize * size_helper();
  sz->_vtab_bytes = wordSize * vtable_length();
  sz->_itab_bytes = wordSize * itable_length();
  sz->_nonstatic_oopmap_bytes = wordSize * nonstatic_oop_map_size();

  int n = 0;
  n += (sz->_methods_array_bytes         = sz->count_array(methods()));
  n += (sz->_method_ordering_bytes       = sz->count_array(method_ordering()));
  n += (sz->_local_interfaces_bytes      = sz->count_array(local_interfaces()));
  n += (sz->_transitive_interfaces_bytes = sz->count_array(transitive_interfaces()));
  n += (sz->_fields_bytes                = sz->count_array(fields()));
  n += (sz->_inner_classes_bytes         = sz->count_array(inner_classes()));
  n += (sz->_nest_members_bytes          = sz->count_array(nest_members()));
  sz->_ro_bytes += n;

  const ConstantPool* cp = constants();
  if (cp) {
    cp->collect_statistics(sz);
  }

  const Annotations* anno = annotations();
  if (anno) {
    anno->collect_statistics(sz);
  }

  const Array<Method*>* methods_array = methods();
  if (methods()) {
    for (int i = 0; i < methods_array->length(); i++) {
      Method* method = methods_array->at(i);
      if (method) {
        sz->_method_count ++;
        method->collect_statistics(sz);
      }
    }
  }
}
#endif // INCLUDE_SERVICES

// Verification

class VerifyFieldClosure: public BasicOopIterateClosure {
 protected:
  template <class T> void do_oop_work(T* p) {
    oop obj = RawAccess<>::oop_load(p);
    if (!oopDesc::is_oop_or_null(obj)) {
      tty->print_cr("Failed: " PTR_FORMAT " -> " PTR_FORMAT, p2i(p), p2i(obj));
      Universe::print_on(tty);
      guarantee(false, "boom");
    }
  }
 public:
  virtual void do_oop(oop* p)       { VerifyFieldClosure::do_oop_work(p); }
  virtual void do_oop(narrowOop* p) { VerifyFieldClosure::do_oop_work(p); }
};

void InstanceKlass::verify_on(outputStream* st) {
#ifndef PRODUCT
  // Avoid redundant verifies, this really should be in product.
  if (_verify_count == Universe::verify_count()) return;
  _verify_count = Universe::verify_count();
#endif

  // Verify Klass
  Klass::verify_on(st);

  // Verify that klass is present in ClassLoaderData
  guarantee(class_loader_data()->contains_klass(this),
            "this class isn't found in class loader data");

  // Verify vtables
  if (is_linked()) {
    // $$$ This used to be done only for m/s collections.  Doing it
    // always seemed a valid generalization.  (DLD -- 6/00)
    vtable().verify(st);
  }

  // Verify first subklass
  if (subklass() != NULL) {
    guarantee(subklass()->is_klass(), "should be klass");
  }

  // Verify siblings
  Klass* super = this->super();
  Klass* sib = next_sibling();
  if (sib != NULL) {
    if (sib == this) {
      fatal("subclass points to itself " PTR_FORMAT, p2i(sib));
    }

    guarantee(sib->is_klass(), "should be klass");
    // TODO: (DCEVM) explain
    guarantee(sib->super() == super || super->newest_version() == SystemDictionary::Object_klass(), "siblings should have same superklass");
  }

  // Verify implementor fields requires the Compile_lock, but this is sometimes
  // called inside a safepoint, so don't verify.

  // Verify local interfaces
  if (local_interfaces()) {
    Array<Klass*>* local_interfaces = this->local_interfaces();
    for (int j = 0; j < local_interfaces->length(); j++) {
      Klass* e = local_interfaces->at(j);
      guarantee(e->is_klass() && e->is_interface(), "invalid local interface");
    }
  }

  // Verify transitive interfaces
  if (transitive_interfaces() != NULL) {
    Array<Klass*>* transitive_interfaces = this->transitive_interfaces();
    for (int j = 0; j < transitive_interfaces->length(); j++) {
      Klass* e = transitive_interfaces->at(j);
      guarantee(e->is_klass() && e->is_interface(), "invalid transitive interface");
    }
  }

  // Verify methods
  if (methods() != NULL) {
    Array<Method*>* methods = this->methods();
    for (int j = 0; j < methods->length(); j++) {
      guarantee(methods->at(j)->is_method(), "non-method in methods array");
    }
    for (int j = 0; j < methods->length() - 1; j++) {
      Method* m1 = methods->at(j);
      Method* m2 = methods->at(j + 1);
      guarantee(m1->name()->fast_compare(m2->name()) <= 0, "methods not sorted correctly");
    }
  }

  // Verify method ordering
  if (method_ordering() != NULL) {
    Array<int>* method_ordering = this->method_ordering();
    int length = method_ordering->length();
    if (JvmtiExport::can_maintain_original_method_order() ||
        ((UseSharedSpaces || DumpSharedSpaces) && length != 0)) {
      guarantee(length == methods()->length(), "invalid method ordering length");
      jlong sum = 0;
      for (int j = 0; j < length; j++) {
        int original_index = method_ordering->at(j);
        guarantee(original_index >= 0, "invalid method ordering index");
        guarantee(original_index < length, "invalid method ordering index");
        sum += original_index;
      }
      // Verify sum of indices 0,1,...,length-1
      guarantee(sum == ((jlong)length*(length-1))/2, "invalid method ordering sum");
    } else {
      guarantee(length == 0, "invalid method ordering length");
    }
  }

  // Verify default methods
  if (default_methods() != NULL) {
    Array<Method*>* methods = this->default_methods();
    for (int j = 0; j < methods->length(); j++) {
      guarantee(methods->at(j)->is_method(), "non-method in methods array");
    }
    for (int j = 0; j < methods->length() - 1; j++) {
      Method* m1 = methods->at(j);
      Method* m2 = methods->at(j + 1);
      guarantee(m1->name()->fast_compare(m2->name()) <= 0, "methods not sorted correctly");
    }
  }

  // Verify JNI static field identifiers
  if (jni_ids() != NULL) {
    jni_ids()->verify(this);
  }

  // Verify other fields
  if (array_klasses() != NULL) {
    guarantee(array_klasses()->is_klass(), "should be klass");
  }
  if (constants() != NULL) {
    guarantee(constants()->is_constantPool(), "should be constant pool");
  }
  const Klass* host = host_klass();
  if (host != NULL) {
    guarantee(host->is_klass(), "should be klass");
  }
}

void InstanceKlass::oop_verify_on(oop obj, outputStream* st) {
  Klass::oop_verify_on(obj, st);
  VerifyFieldClosure blk;
  obj->oop_iterate(&blk);
}


// JNIid class for jfieldIDs only
// Note to reviewers:
// These JNI functions are just moved over to column 1 and not changed
// in the compressed oops workspace.
JNIid::JNIid(Klass* holder, int offset, JNIid* next) {
  _holder = holder;
  _offset = offset;
  _next = next;
  debug_only(_is_static_field_id = false;)
}


JNIid* JNIid::find(int offset) {
  JNIid* current = this;
  while (current != NULL) {
    if (current->offset() == offset) return current;
    current = current->next();
  }
  return NULL;
}

void JNIid::deallocate(JNIid* current) {
  while (current != NULL) {
    JNIid* next = current->next();
    delete current;
    current = next;
  }
}


void JNIid::verify(Klass* holder) {
  int first_field_offset  = InstanceMirrorKlass::offset_of_static_fields();
  int end_field_offset;
  end_field_offset = first_field_offset + (InstanceKlass::cast(holder)->static_field_size() * wordSize);

  JNIid* current = this;
  while (current != NULL) {
    guarantee(current->holder() == holder, "Invalid klass in JNIid");
#ifdef ASSERT
    int o = current->offset();
    if (current->is_static_field_id()) {
      guarantee(o >= first_field_offset  && o < end_field_offset,  "Invalid static field offset in JNIid");
    }
#endif
    current = current->next();
  }
}

#ifdef ASSERT
void InstanceKlass::set_init_state(ClassState state) {
  bool good_state = is_shared() ? (_init_state <= state)
                                               : (_init_state < state);
  assert(good_state || state == allocated, "illegal state transition");
  assert(_init_thread == NULL, "should be cleared before state change");
  _init_state = (u1)state;
}
#endif

#if INCLUDE_JVMTI

// RedefineClasses() support for previous versions

// Globally, there is at least one previous version of a class to walk
// during class unloading, which is saved because old methods in the class
// are still running.   Otherwise the previous version list is cleaned up.
bool InstanceKlass::_has_previous_versions = false;

// Returns true if there are previous versions of a class for class
// unloading only. Also resets the flag to false. purge_previous_version
// will set the flag to true if there are any left, i.e., if there's any
// work to do for next time. This is to avoid the expensive code cache
// walk in CLDG::do_unloading().
bool InstanceKlass::has_previous_versions_and_reset() {
  bool ret = _has_previous_versions;
  log_trace(redefine, class, iklass, purge)("Class unloading: has_previous_versions = %s",
     ret ? "true" : "false");
  _has_previous_versions = false;
  return ret;
}

// Purge previous versions before adding new previous versions of the class and
// during class unloading.
void InstanceKlass::purge_previous_version_list() {
  assert(SafepointSynchronize::is_at_safepoint(), "only called at safepoint");
  assert(has_been_redefined(), "Should only be called for main class");

  // Quick exit.
  if (previous_versions() == NULL) {
    return;
  }

  // This klass has previous versions so see what we can cleanup
  // while it is safe to do so.

  int deleted_count = 0;    // leave debugging breadcrumbs
  int live_count = 0;
  ClassLoaderData* loader_data = class_loader_data();
  assert(loader_data != NULL, "should never be null");

  ResourceMark rm;
  log_trace(redefine, class, iklass, purge)("%s: previous versions", external_name());

  // previous versions are linked together through the InstanceKlass
  InstanceKlass* pv_node = previous_versions();
  InstanceKlass* last = this;
  int version = 0;

  // check the previous versions list
  for (; pv_node != NULL; ) {

    ConstantPool* pvcp = pv_node->constants();
    assert(pvcp != NULL, "cp ref was unexpectedly cleared");

    if (!pvcp->on_stack()) {
      // If the constant pool isn't on stack, none of the methods
      // are executing.  Unlink this previous_version.
      // The previous version InstanceKlass is on the ClassLoaderData deallocate list
      // so will be deallocated during the next phase of class unloading.
      log_trace(redefine, class, iklass, purge)
        ("previous version " INTPTR_FORMAT " is dead.", p2i(pv_node));
      // For debugging purposes.
      pv_node->set_is_scratch_class();
      // Unlink from previous version list.
      assert(pv_node->class_loader_data() == loader_data, "wrong loader_data");
      InstanceKlass* next = pv_node->previous_versions();
      pv_node->link_previous_versions(NULL);   // point next to NULL
      last->link_previous_versions(next);
      // Add to the deallocate list after unlinking
      loader_data->add_to_deallocate_list(pv_node);
      pv_node = next;
      deleted_count++;
      version++;
      continue;
    } else {
      log_trace(redefine, class, iklass, purge)("previous version " INTPTR_FORMAT " is alive", p2i(pv_node));
      assert(pvcp->pool_holder() != NULL, "Constant pool with no holder");
      guarantee (!loader_data->is_unloading(), "unloaded classes can't be on the stack");
      live_count++;
      // found a previous version for next time we do class unloading
      _has_previous_versions = true;
    }

    // At least one method is live in this previous version.
    // Reset dead EMCP methods not to get breakpoints.
    // All methods are deallocated when all of the methods for this class are no
    // longer running.
    Array<Method*>* method_refs = pv_node->methods();
    if (method_refs != NULL) {
      log_trace(redefine, class, iklass, purge)("previous methods length=%d", method_refs->length());
      for (int j = 0; j < method_refs->length(); j++) {
        Method* method = method_refs->at(j);

        if (!method->on_stack()) {
          // no breakpoints for non-running methods
          if (method->is_running_emcp()) {
            method->set_running_emcp(false);
          }
        } else {
          assert (method->is_obsolete() || method->is_running_emcp(),
                  "emcp method cannot run after emcp bit is cleared");
          log_trace(redefine, class, iklass, purge)
            ("purge: %s(%s): prev method @%d in version @%d is alive",
             method->name()->as_C_string(), method->signature()->as_C_string(), j, version);
        }
      }
    }
    // next previous version
    last = pv_node;
    pv_node = pv_node->previous_versions();
    version++;
  }
  log_trace(redefine, class, iklass, purge)
    ("previous version stats: live=%d, deleted=%d", live_count, deleted_count);
}

void InstanceKlass::mark_newly_obsolete_methods(Array<Method*>* old_methods,
                                                int emcp_method_count) {
  int obsolete_method_count = old_methods->length() - emcp_method_count;

  if (emcp_method_count != 0 && obsolete_method_count != 0 &&
      _previous_versions != NULL) {
    // We have a mix of obsolete and EMCP methods so we have to
    // clear out any matching EMCP method entries the hard way.
    int local_count = 0;
    for (int i = 0; i < old_methods->length(); i++) {
      Method* old_method = old_methods->at(i);
      if (old_method->is_obsolete()) {
        // only obsolete methods are interesting
        Symbol* m_name = old_method->name();
        Symbol* m_signature = old_method->signature();

        // previous versions are linked together through the InstanceKlass
        int j = 0;
        for (InstanceKlass* prev_version = _previous_versions;
             prev_version != NULL;
             prev_version = prev_version->previous_versions(), j++) {

          Array<Method*>* method_refs = prev_version->methods();
          for (int k = 0; k < method_refs->length(); k++) {
            Method* method = method_refs->at(k);

            if (!method->is_obsolete() &&
                method->name() == m_name &&
                method->signature() == m_signature) {
              // The current RedefineClasses() call has made all EMCP
              // versions of this method obsolete so mark it as obsolete
              log_trace(redefine, class, iklass, add)
                ("%s(%s): flush obsolete method @%d in version @%d",
                 m_name->as_C_string(), m_signature->as_C_string(), k, j);

              method->set_is_obsolete();
              break;
            }
          }

          // The previous loop may not find a matching EMCP method, but
          // that doesn't mean that we can optimize and not go any
          // further back in the PreviousVersion generations. The EMCP
          // method for this generation could have already been made obsolete,
          // but there still may be an older EMCP method that has not
          // been made obsolete.
        }

        if (++local_count >= obsolete_method_count) {
          // no more obsolete methods so bail out now
          break;
        }
      }
    }
  }
}

// Save the scratch_class as the previous version if any of the methods are running.
// The previous_versions are used to set breakpoints in EMCP methods and they are
// also used to clean MethodData links to redefined methods that are no longer running.
void InstanceKlass::add_previous_version(InstanceKlass* scratch_class,
                                         int emcp_method_count) {
  assert(Thread::current()->is_VM_thread(),
         "only VMThread can add previous versions");

  ResourceMark rm;
  log_trace(redefine, class, iklass, add)
    ("adding previous version ref for %s, EMCP_cnt=%d", scratch_class->external_name(), emcp_method_count);

  // Clean out old previous versions for this class
  purge_previous_version_list();

  // Mark newly obsolete methods in remaining previous versions.  An EMCP method from
  // a previous redefinition may be made obsolete by this redefinition.
  Array<Method*>* old_methods = scratch_class->methods();
  mark_newly_obsolete_methods(old_methods, emcp_method_count);

  // If the constant pool for this previous version of the class
  // is not marked as being on the stack, then none of the methods
  // in this previous version of the class are on the stack so
  // we don't need to add this as a previous version.
  ConstantPool* cp_ref = scratch_class->constants();
  if (!cp_ref->on_stack()) {
    log_trace(redefine, class, iklass, add)("scratch class not added; no methods are running");
    // For debugging purposes.
    scratch_class->set_is_scratch_class();
    scratch_class->class_loader_data()->add_to_deallocate_list(scratch_class);
    return;
  }

  if (emcp_method_count != 0) {
    // At least one method is still running, check for EMCP methods
    for (int i = 0; i < old_methods->length(); i++) {
      Method* old_method = old_methods->at(i);
      if (!old_method->is_obsolete() && old_method->on_stack()) {
        // if EMCP method (not obsolete) is on the stack, mark as EMCP so that
        // we can add breakpoints for it.

        // We set the method->on_stack bit during safepoints for class redefinition
        // and use this bit to set the is_running_emcp bit.
        // After the safepoint, the on_stack bit is cleared and the running emcp
        // method may exit.   If so, we would set a breakpoint in a method that
        // is never reached, but this won't be noticeable to the programmer.
        old_method->set_running_emcp(true);
        log_trace(redefine, class, iklass, add)
          ("EMCP method %s is on_stack " INTPTR_FORMAT, old_method->name_and_sig_as_C_string(), p2i(old_method));
      } else if (!old_method->is_obsolete()) {
        log_trace(redefine, class, iklass, add)
          ("EMCP method %s is NOT on_stack " INTPTR_FORMAT, old_method->name_and_sig_as_C_string(), p2i(old_method));
      }
    }
  }

  // Add previous version if any methods are still running.
  // Set has_previous_version flag for processing during class unloading.
  _has_previous_versions = true;
  log_trace(redefine, class, iklass, add) ("scratch class added; one of its methods is on_stack.");
  assert(scratch_class->previous_versions() == NULL, "shouldn't have a previous version");
  scratch_class->link_previous_versions(previous_versions());
  link_previous_versions(scratch_class);
} // end add_previous_version()

#endif // INCLUDE_JVMTI

Method* InstanceKlass::method_with_idnum(int idnum) {
  Method* m = NULL;
  if (idnum < methods()->length()) {
    m = methods()->at(idnum);
  }
  if (m == NULL || m->method_idnum() != idnum) {
    for (int index = 0; index < methods()->length(); ++index) {
      m = methods()->at(index);
      if (m->method_idnum() == idnum) {
        return m;
      }
    }
    // None found, return null for the caller to handle.
    return NULL;
  }
  return m;
}


Method* InstanceKlass::method_with_orig_idnum(int idnum) {
  if (idnum >= methods()->length()) {
    return NULL;
  }
  Method* m = methods()->at(idnum);
  if (m != NULL && m->orig_method_idnum() == idnum) {
    return m;
  }
  // Obsolete method idnum does not match the original idnum
  for (int index = 0; index < methods()->length(); ++index) {
    m = methods()->at(index);
    if (m->orig_method_idnum() == idnum) {
      return m;
    }
  }
  // None found, return null for the caller to handle.
  return NULL;
}


Method* InstanceKlass::method_with_orig_idnum(int idnum, int version) {
  InstanceKlass* holder = get_klass_version(version);
  if (holder == NULL) {
    return NULL; // The version of klass is gone, no method is found
  }
  Method* method = holder->method_with_orig_idnum(idnum);
  return method;
}

#if INCLUDE_JVMTI
JvmtiCachedClassFileData* InstanceKlass::get_cached_class_file() {
  if (MetaspaceShared::is_in_shared_metaspace(_cached_class_file)) {
    // Ignore the archived class stream data
    return NULL;
  } else {
    return _cached_class_file;
  }
}

jint InstanceKlass::get_cached_class_file_len() {
  return VM_RedefineClasses::get_cached_class_file_len(_cached_class_file);
}

unsigned char * InstanceKlass::get_cached_class_file_bytes() {
  return VM_RedefineClasses::get_cached_class_file_bytes(_cached_class_file);
}

#if INCLUDE_CDS
JvmtiCachedClassFileData* InstanceKlass::get_archived_class_data() {
  if (DumpSharedSpaces) {
    return _cached_class_file;
  } else {
    assert(this->is_shared(), "class should be shared");
    if (MetaspaceShared::is_in_shared_metaspace(_cached_class_file)) {
      return _cached_class_file;
    } else {
      return NULL;
    }
  }
}
#endif
#endif
