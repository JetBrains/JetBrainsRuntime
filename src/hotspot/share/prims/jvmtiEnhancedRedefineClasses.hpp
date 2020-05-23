/*
 * Copyright (c) 2003, 2016, Oracle and/or its affiliates. All rights reserved.
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
 *
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_PRIMS_JVMTIREDEFINECLASSES2_HPP
#define SHARE_VM_PRIMS_JVMTIREDEFINECLASSES2_HPP

#include "jvmtifiles/jvmtiEnv.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/objArrayOop.hpp"
#include "gc/shared/vmGCOperations.hpp"
#include "../../../java.base/unix/native/include/jni_md.h"

//
// Enhanced class redefiner.
//
// This class implements VM_GC_Operation - the usual usage should be:
//     VM_EnhancedRedefineClasses op(class_count, class_definitions, jvmti_class_load_kind_redefine);
//     VMThread::execute(&op);
// Which in turn runs:
//   - doit_prologue() - calculate all affected classes (add subclasses etc) and load new class versions
//   - doit() - main redefition, adjust existing objects on the heap, clear caches
//   - doit_epilogue() - cleanup
class VM_EnhancedRedefineClasses: public VM_GC_Operation {
 private:
  // These static fields are needed by ClassLoaderDataGraph::classes_do()
  // facility and the AdjustCpoolCacheAndVtable helper:
  static Array<Method*>* _old_methods;
  static Array<Method*>* _new_methods;
  static Method**      _matching_old_methods;
  static Method**      _matching_new_methods;
  static Method**      _deleted_methods;
  static Method**      _added_methods;
  static int             _matching_methods_length;
  static int             _deleted_methods_length;
  static int             _added_methods_length;
  static Klass*          _the_class_oop;

  // The instance fields are used to pass information from
  // doit_prologue() to doit() and doit_epilogue().
  jint                        _class_count;
  const jvmtiClassDefinition *_class_defs;  // ptr to _class_count defs

  // This operation is used by both RedefineClasses and
  // RetransformClasses.  Indicate which.
  JvmtiClassLoadKind          _class_load_kind;

  GrowableArray<InstanceKlass*>*      _new_classes;
  jvmtiError                  _res;

  // Set if any of the InstanceKlasses have entries in the ResolvedMethodTable
  // to avoid walking after redefinition if the redefined classes do not
  // have any entries.
  bool _any_class_has_resolved_methods;

  // Enhanced class redefinition, affected klasses contain all classes which should be redefined
  // either because of redefine, class hierarchy or interface change
  GrowableArray<Klass*>*      _affected_klasses;

  int                         _max_redefinition_flags;

  // Performance measurement support. These timers do not cover all
  // the work done for JVM/TI RedefineClasses() but they do cover
  // the heavy lifting.
  elapsedTimer  _timer_rsc_phase1;
  elapsedTimer  _timer_rsc_phase2;
  elapsedTimer  _timer_vm_op_prologue;

  // These routines are roughly in call order unless otherwise noted.

  // Load and link new classes (either redefined or affected by redefinition - subclass, ...)
  //
  // - find sorted affected classes
  // - resolve new class
  // - calculate redefine flags (field change, method change, supertype change, ...)
  // - calculate modified fields and mapping to old fields
  // - link new classes
  //
  // The result is sotred in _affected_klasses(old definitions) and _new_classes(new definitions) arrays.
  jvmtiError load_new_class_versions(TRAPS);

  // Searches for all affected classes and performs a sorting such tha
  // a supertype is always before a subtype.
  jvmtiError find_sorted_affected_classes(TRAPS);

  jvmtiError do_topological_class_sorting(TRAPS);

  jvmtiError find_class_bytes(InstanceKlass* the_class, const unsigned char **class_bytes, jint *class_byte_count, jboolean *not_changed);
  int calculate_redefinition_flags(InstanceKlass* new_class);
  void calculate_instance_update_information(Klass* new_version);

  void rollback();
  static void mark_as_scavengable(nmethod* nm);
  static void unpatch_bytecode(Method* method);
  static void fix_invoke_method(Method* method);

  // Figure out which new methods match old methods in name and signature,
  // which methods have been added, and which are no longer present
  void compute_added_deleted_matching_methods();

  // Change jmethodIDs to point to the new methods
  void update_jmethod_ids();

  // marking methods as old and/or obsolete
  void check_methods_and_mark_as_obsolete();
  void transfer_old_native_function_registrations(InstanceKlass* the_class);

  // Install the redefinition of a class
  void redefine_single_class(InstanceKlass* new_class_oop, TRAPS);

  // Increment the classRedefinedCount field in the specific InstanceKlass
  // and in all direct and indirect subclasses.
  void increment_class_counter(InstanceKlass *ik, TRAPS);

  void flush_dependent_code(InstanceKlass* k_h, TRAPS);

  static void check_class(InstanceKlass* k_oop, TRAPS);

  static void dump_methods();

  // Check that there are no old or obsolete methods
  class CheckClass : public KlassClosure {
    Thread* _thread;
   public:
    CheckClass(Thread* t) : _thread(t) {}
    void do_klass(Klass* k);
  };

  // Unevolving classes may point to methods of the_class directly
  // from their constant pool caches, itables, and/or vtables. We
  // use the ClassLoaderDataGraph::classes_do() facility and this helper
  // to fix up these pointers.
  class ClearCpoolCacheAndUnpatch : public KlassClosure {
    Thread* _thread;
   public:
    ClearCpoolCacheAndUnpatch(Thread* t) : _thread(t) {}
    void do_klass(Klass* k);
  };

  // Clean MethodData out
  class MethodDataCleaner : public KlassClosure {
   public:
    MethodDataCleaner() {}
    void do_klass(Klass* k);
  };
 public:
  VM_EnhancedRedefineClasses(jint class_count,
                     const jvmtiClassDefinition *class_defs,
                     JvmtiClassLoadKind class_load_kind);
  VMOp_Type type() const { return VMOp_RedefineClasses; }
  bool doit_prologue();
  void doit();
  void doit_epilogue();

  bool allow_nested_vm_operations() const        { return true; }
  jvmtiError check_error()                       { return _res; }

  // Modifiable test must be shared between IsModifiableClass query
  // and redefine implementation
  static bool is_modifiable_class(oop klass_mirror);
};
#endif // SHARE_VM_PRIMS_JVMTIREDEFINECLASSES2_HPP
