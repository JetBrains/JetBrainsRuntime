/*
 * Copyright (c) 2016, 2019, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/classLoaderData.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/moduleEntry.hpp"
#include "classfile/packageEntry.hpp"
#include "classfile/symbolTable.hpp"
#include "jfr/jfr.hpp"
#include "jfr/jni/jfrGetAllEventClasses.hpp"
#include "jfr/leakprofiler/checkpoint/objectSampleCheckpoint.hpp"
#include "jfr/recorder/checkpoint/types/jfrTypeSet.hpp"
#include "jfr/recorder/checkpoint/types/jfrTypeSetUtils.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceId.inline.hpp"
#include "jfr/utilities/jfrHashtable.hpp"
#include "jfr/utilities/jfrTypes.hpp"
#include "jfr/writers/jfrTypeWriterHost.hpp"
#include "memory/iterator.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/accessFlags.hpp"
#include "utilities/bitMap.inline.hpp"

typedef const Klass* KlassPtr;
typedef const PackageEntry* PkgPtr;
typedef const ModuleEntry* ModPtr;
typedef const ClassLoaderData* CldPtr;
typedef const Method* MethodPtr;
typedef const Symbol* SymbolPtr;
typedef const JfrSymbolId::SymbolEntry* SymbolEntryPtr;
typedef const JfrSymbolId::CStringEntry* CStringEntryPtr;

static JfrCheckpointWriter* _writer = NULL;
static JfrCheckpointWriter* _leakp_writer = NULL;
static JfrArtifactSet* _artifacts = NULL;
static JfrArtifactClosure* _subsystem_callback = NULL;
static bool _class_unload = false;
static bool _flushpoint = false;
static bool _clear_artifacts = false;

// incremented on each rotation
static u8 checkpoint_id = 1;

// creates a unique id by combining a checkpoint relative symbol id (2^24)
// with the current checkpoint id (2^40)
#define CREATE_SYMBOL_ID(sym_id) (((u8)((checkpoint_id << 24) | sym_id)))

static traceid create_symbol_id(traceid artifact_id) {
  return artifact_id != 0 ? CREATE_SYMBOL_ID(artifact_id) : 0;
}

static bool current_epoch() {
  return _class_unload;
}

static bool previous_epoch() {
  return !current_epoch();
}

static bool is_initial_typeset_for_chunk() {
  return _clear_artifacts && !_class_unload;
}

static bool is_complete() {
  return !_artifacts->has_klass_entries() && current_epoch();
}

static traceid mark_symbol(KlassPtr klass, bool leakp) {
  return klass != NULL ? create_symbol_id(_artifacts->mark(klass, leakp)) : 0;
}

static traceid mark_symbol(Symbol* symbol, bool leakp) {
  return symbol != NULL ? create_symbol_id(_artifacts->mark(symbol, leakp)) : 0;
}

static traceid get_bootstrap_name(bool leakp) {
  return create_symbol_id(_artifacts->bootstrap_name(leakp));
}

static const char* primitive_name(KlassPtr type_array_klass) {
  switch (type_array_klass->name()->base()[1]) {
    case JVM_SIGNATURE_BOOLEAN: return "boolean";
    case JVM_SIGNATURE_BYTE: return "byte";
    case JVM_SIGNATURE_CHAR: return "char";
    case JVM_SIGNATURE_SHORT: return "short";
    case JVM_SIGNATURE_INT: return "int";
    case JVM_SIGNATURE_LONG: return "long";
    case JVM_SIGNATURE_FLOAT: return "float";
    case JVM_SIGNATURE_DOUBLE: return "double";
  }
  assert(false, "invalid type array klass");
  return NULL;
}

static Symbol* primitive_symbol(KlassPtr type_array_klass) {
  if (type_array_klass == NULL) {
    // void.class
    static Symbol* const void_class_name = SymbolTable::probe("void", 4);
    assert(void_class_name != NULL, "invariant");
    return void_class_name;
  }
  const char* const primitive_type_str = primitive_name(type_array_klass);
  assert(primitive_type_str != NULL, "invariant");
  Symbol* const primitive_type_sym = SymbolTable::probe(primitive_type_str, (int)strlen(primitive_type_str));
  assert(primitive_type_sym != NULL, "invariant");
  return primitive_type_sym;
}

template <typename T>
static traceid artifact_id(const T* ptr) {
  assert(ptr != NULL, "invariant");
  return TRACE_ID(ptr);
}

static traceid package_id(KlassPtr klass, bool leakp) {
  assert(klass != NULL, "invariant");
  PkgPtr pkg_entry = klass->package();
  if (pkg_entry == NULL) {
    return 0;
  }
  if (leakp) {
    SET_LEAKP(pkg_entry);
  }
  // package implicitly tagged already
  return artifact_id(pkg_entry);
}

static traceid module_id(PkgPtr pkg, bool leakp) {
  assert(pkg != NULL, "invariant");
  ModPtr module_entry = pkg->module();
  if (module_entry == NULL) {
    return 0;
  }
  if (leakp) {
    SET_LEAKP(module_entry);
  } else {
    SET_TRANSIENT(module_entry);
  }
  return artifact_id(module_entry);
}

static traceid method_id(KlassPtr klass, MethodPtr method) {
  assert(klass != NULL, "invariant");
  assert(method != NULL, "invariant");
  return METHOD_ID(klass, method);
}

static traceid cld_id(CldPtr cld, bool leakp) {
  assert(cld != NULL, "invariant");
  assert(!cld->is_anonymous(), "invariant");
  if (leakp) {
    SET_LEAKP(cld);
  } else {
    SET_TRANSIENT(cld);
  }
  return artifact_id(cld);
}

template <typename T>
static s4 get_flags(const T* ptr) {
  assert(ptr != NULL, "invariant");
  return ptr->access_flags().get_flags();
}

// Same as JVM_GetClassModifiers
static u4 get_primitive_flags() {
  return JVM_ACC_ABSTRACT | JVM_ACC_FINAL | JVM_ACC_PUBLIC;
}

static bool is_unsafe_anonymous(const Klass* klass) {
  assert(klass != NULL, "invariant");
  return klass->is_instance_klass() && ((const InstanceKlass*)klass)->is_anonymous();
}

static ClassLoaderData* get_cld(const Klass* klass) {
  assert(klass != NULL, "invariant");
  return is_unsafe_anonymous(klass) ?
    InstanceKlass::cast(klass)->host_klass()->class_loader_data() : klass->class_loader_data();
}

template <typename T>
static void set_serialized(const T* ptr) {
  assert(ptr != NULL, "invariant");
  SET_SERIALIZED(ptr);
  assert(IS_SERIALIZED(ptr), "invariant");
}

/*
 * In C++03, functions used as template parameters must have external linkage;
 * this restriction was removed in C++11. Change back to "static" and
 * rename functions when C++11 becomes available.
 *
 * The weird naming is an effort to decrease the risk of name clashes.
 */

static int write_klass(JfrCheckpointWriter* writer, KlassPtr klass, bool leakp) {
  assert(writer != NULL, "invariant");
  assert(_artifacts != NULL, "invariant");
  assert(klass != NULL, "invariant");
  traceid pkg_id = 0;
  KlassPtr theklass = klass;
  if (theklass->is_objArray_klass()) {
    const ObjArrayKlass* obj_arr_klass = ObjArrayKlass::cast(klass);
    theklass = obj_arr_klass->bottom_klass();
  }
  if (theklass->is_instance_klass()) {
    pkg_id = package_id(theklass, leakp);
  } else {
    assert(theklass->is_typeArray_klass(), "invariant");
  }
  writer->write(artifact_id(klass));
  writer->write(cld_id(get_cld(klass), leakp));
  writer->write(mark_symbol(klass, leakp));
  writer->write(pkg_id);
  writer->write(get_flags(klass));
  return 1;
}

int write__klass(JfrCheckpointWriter* writer, const void* k) {
  assert(k != NULL, "invariant");
  KlassPtr klass = (KlassPtr)k;
  set_serialized(klass);
  return write_klass(writer, klass, false);
}

int write__klass__leakp(JfrCheckpointWriter* writer, const void* k) {
  assert(k != NULL, "invariant");
  KlassPtr klass = (KlassPtr)k;
  return write_klass(writer, klass, true);
}

static bool is_implied(const Klass* klass) {
  assert(klass != NULL, "invariant");
  return klass->is_subclass_of(SystemDictionary::ClassLoader_klass()) || klass == SystemDictionary::Object_klass();
}

static void do_implied(Klass* klass) {
  assert(klass != NULL, "invariant");
  if (is_implied(klass)) {
    if (_leakp_writer != NULL) {
      SET_LEAKP(klass);
    }
    _subsystem_callback->do_artifact(klass);
  }
}

static void do_unloaded_klass(Klass* klass) {
  assert(klass != NULL, "invariant");
  assert(_subsystem_callback != NULL, "invariant");
  if (IS_JDK_JFR_EVENT_SUBKLASS(klass)) {
    JfrEventClasses::increment_unloaded_event_class();
  }
  if (USED_THIS_EPOCH(klass)) {
    ObjectSampleCheckpoint::on_klass_unload(klass);
    _subsystem_callback->do_artifact(klass);
    return;
  }
  do_implied(klass);
}

static void do_klass(Klass* klass) {
  assert(klass != NULL, "invariant");
  assert(_subsystem_callback != NULL, "invariant");
  if (current_epoch()) {
    if (USED_THIS_EPOCH(klass)) {
      _subsystem_callback->do_artifact(klass);
      return;
    }
  } else {
    if (USED_PREV_EPOCH(klass)) {
      _subsystem_callback->do_artifact(klass);
      return;
    }
  }
  do_implied(klass);
}

static traceid primitive_id(KlassPtr array_klass) {
  if (array_klass == NULL) {
    // The first klass id is reserved for the void.class.
    return MaxJfrEventId + 101;
  }
  // Derive the traceid for a primitive mirror from its associated array klass (+1).
  return JfrTraceId::get(array_klass) + 1;
}

static void write_primitive(JfrCheckpointWriter* writer, KlassPtr type_array_klass) {
  assert(writer != NULL, "invariant");
  assert(_artifacts != NULL, "invariant");
  writer->write(primitive_id(type_array_klass));
  writer->write(cld_id(get_cld(Universe::boolArrayKlassObj()), false));
  writer->write(mark_symbol(primitive_symbol(type_array_klass), false));
  writer->write(package_id(Universe::boolArrayKlassObj(), false));
  writer->write(get_primitive_flags());
}

static int primitives_count = 9;

// A mirror representing a primitive class (e.g. int.class) has no reified Klass*,
// instead it has an associated TypeArrayKlass* (e.g. int[].class).
// We can use the TypeArrayKlass* as a proxy for deriving the id of the primitive class.
// The exception is the void.class, which has neither a Klass* nor a TypeArrayKlass*.
// It will use a reserved constant.
static void do_primitives() {
  // Only write the primitive classes once per chunk.
  if (is_initial_typeset_for_chunk()) {
    write_primitive(_writer, Universe::boolArrayKlassObj());
    write_primitive(_writer, Universe::byteArrayKlassObj());
    write_primitive(_writer, Universe::charArrayKlassObj());
    write_primitive(_writer, Universe::shortArrayKlassObj());
    write_primitive(_writer, Universe::intArrayKlassObj());
    write_primitive(_writer, Universe::longArrayKlassObj());
    write_primitive(_writer, Universe::singleArrayKlassObj());
    write_primitive(_writer, Universe::doubleArrayKlassObj());
    write_primitive(_writer, NULL); // void.class
  }
}

static void do_klasses() {
  if (_class_unload) {
    ClassLoaderDataGraph::classes_unloading_do(&do_unloaded_klass);
    return;
  }
  ClassLoaderDataGraph::classes_do(&do_klass);
  do_primitives();
}

typedef SerializePredicate<KlassPtr> KlassPredicate;
typedef JfrPredicatedTypeWriterImplHost<KlassPtr, KlassPredicate, write__klass> KlassWriterImpl;
typedef JfrTypeWriterHost<KlassWriterImpl, TYPE_CLASS> KlassWriter;
typedef CompositeFunctor<KlassPtr, KlassWriter, KlassArtifactRegistrator> KlassWriterRegistration;
typedef JfrArtifactCallbackHost<KlassPtr, KlassWriterRegistration> KlassCallback;

template <>
class LeakPredicate<const Klass*> {
public:
  LeakPredicate(bool class_unload) {}
  bool operator()(const Klass* klass) {
    assert(klass != NULL, "invariant");
    return IS_LEAKP(klass) || is_implied(klass);
  }
};

typedef LeakPredicate<KlassPtr> LeakKlassPredicate;
typedef JfrPredicatedTypeWriterImplHost<KlassPtr, LeakKlassPredicate, write__klass__leakp> LeakKlassWriterImpl;
typedef JfrTypeWriterHost<LeakKlassWriterImpl, TYPE_CLASS> LeakKlassWriter;

typedef CompositeFunctor<KlassPtr, LeakKlassWriter, KlassWriter> CompositeKlassWriter;
typedef CompositeFunctor<KlassPtr, CompositeKlassWriter, KlassArtifactRegistrator> CompositeKlassWriterRegistration;
typedef JfrArtifactCallbackHost<KlassPtr, CompositeKlassWriterRegistration> CompositeKlassCallback;

static bool write_klasses() {
  assert(!_artifacts->has_klass_entries(), "invariant");
  assert(_writer != NULL, "invariant");
  KlassArtifactRegistrator reg(_artifacts);
  KlassWriter kw(_writer, _class_unload);
  KlassWriterRegistration kwr(&kw, &reg);
  if (_leakp_writer == NULL) {
    KlassCallback callback(&kwr);
    _subsystem_callback = &callback;
    do_klasses();
  } else {
    LeakKlassWriter lkw(_leakp_writer, _class_unload);
    CompositeKlassWriter ckw(&lkw, &kw);
    CompositeKlassWriterRegistration ckwr(&ckw, &reg);
    CompositeKlassCallback callback(&ckwr);
    _subsystem_callback = &callback;
    do_klasses();
  }
  if (is_initial_typeset_for_chunk()) {
    // Because the set of primitives is written outside the callback,
    // their count is not automatically incremented.
    kw.add(primitives_count);
  }
  if (is_complete()) {
    return false;
  }
  _artifacts->tally(kw);
  return true;
}

template <typename T>
static void do_previous_epoch_artifact(JfrArtifactClosure* callback, T* value) {
  assert(callback != NULL, "invariant");
  assert(value != NULL, "invariant");
  if (USED_PREV_EPOCH(value)) {
    callback->do_artifact(value);
    assert(IS_NOT_SERIALIZED(value), "invariant");
    return;
  }
  if (IS_SERIALIZED(value)) {
    CLEAR_SERIALIZED(value);
  }
  assert(IS_NOT_SERIALIZED(value), "invariant");
}

static int write_package(JfrCheckpointWriter* writer, PkgPtr pkg, bool leakp) {
  assert(writer != NULL, "invariant");
  assert(_artifacts != NULL, "invariant");
  assert(pkg != NULL, "invariant");
  writer->write(artifact_id(pkg));
  writer->write(mark_symbol(pkg->name(), leakp));
  writer->write(module_id(pkg, leakp));
  writer->write((bool)pkg->is_exported());
  return 1;
}

int write__package(JfrCheckpointWriter* writer, const void* p) {
  assert(p != NULL, "invariant");
  PkgPtr pkg = (PkgPtr)p;
  set_serialized(pkg);
  return write_package(writer, pkg, false);
}

int write__package__leakp(JfrCheckpointWriter* writer, const void* p) {
  assert(p != NULL, "invariant");
  PkgPtr pkg = (PkgPtr)p;
  CLEAR_LEAKP(pkg);
  return write_package(writer, pkg, true);
}

static void do_package(PackageEntry* entry) {
  do_previous_epoch_artifact(_subsystem_callback, entry);
}

static void do_packages() {
  ClassLoaderDataGraph::packages_do(&do_package);
}

class PackageFieldSelector {
 public:
  typedef PkgPtr TypePtr;
  static TypePtr select(KlassPtr klass) {
    assert(klass != NULL, "invariant");
    return ((InstanceKlass*)klass)->package();
  }
};

typedef SerializePredicate<PkgPtr> PackagePredicate;
typedef JfrPredicatedTypeWriterImplHost<PkgPtr, PackagePredicate, write__package> PackageWriterImpl;
typedef JfrTypeWriterHost<PackageWriterImpl, TYPE_PACKAGE> PackageWriter;
typedef CompositeFunctor<PkgPtr, PackageWriter, ClearArtifact<PkgPtr> > PackageWriterWithClear;
typedef KlassToFieldEnvelope<PackageFieldSelector, PackageWriter> KlassPackageWriter;
typedef JfrArtifactCallbackHost<PkgPtr, PackageWriterWithClear> PackageCallback;

typedef LeakPredicate<PkgPtr> LeakPackagePredicate;
typedef JfrPredicatedTypeWriterImplHost<PkgPtr, LeakPackagePredicate, write__package__leakp> LeakPackageWriterImpl;
typedef JfrTypeWriterHost<LeakPackageWriterImpl, TYPE_PACKAGE> LeakPackageWriter;

typedef CompositeFunctor<PkgPtr, LeakPackageWriter, PackageWriter> CompositePackageWriter;
typedef KlassToFieldEnvelope<PackageFieldSelector, CompositePackageWriter> KlassCompositePackageWriter;
typedef KlassToFieldEnvelope<PackageFieldSelector, PackageWriterWithClear> KlassPackageWriterWithClear;
typedef CompositeFunctor<PkgPtr, CompositePackageWriter, ClearArtifact<PkgPtr> > CompositePackageWriterWithClear;
typedef JfrArtifactCallbackHost<PkgPtr, CompositePackageWriterWithClear> CompositePackageCallback;

static void write_packages() {
  assert(_writer != NULL, "invariant");
  PackageWriter pw(_writer, _class_unload);
  KlassPackageWriter kpw(&pw);
  if (current_epoch()) {
    _artifacts->iterate_klasses(kpw);
    _artifacts->tally(pw);
    return;
  }
  assert(previous_epoch(), "invariant");
  if (_leakp_writer == NULL) {
    _artifacts->iterate_klasses(kpw);
    ClearArtifact<PkgPtr> clear;
    PackageWriterWithClear pwwc(&pw, &clear);
    PackageCallback callback(&pwwc);
    _subsystem_callback = &callback;
    do_packages();
  } else {
    LeakPackageWriter lpw(_leakp_writer, _class_unload);
    CompositePackageWriter cpw(&lpw, &pw);
    KlassCompositePackageWriter kcpw(&cpw);
    _artifacts->iterate_klasses(kcpw);
    ClearArtifact<PkgPtr> clear;
    CompositePackageWriterWithClear cpwwc(&cpw, &clear);
    CompositePackageCallback callback(&cpwwc);
    _subsystem_callback = &callback;
    do_packages();
  }
  _artifacts->tally(pw);
}

static int write_module(JfrCheckpointWriter* writer, ModPtr mod, bool leakp) {
  assert(mod != NULL, "invariant");
  assert(_artifacts != NULL, "invariant");
  writer->write(artifact_id(mod));
  writer->write(mark_symbol(mod->name(), leakp));
  writer->write(mark_symbol(mod->version(), leakp));
  writer->write(mark_symbol(mod->location(), leakp));
  writer->write(cld_id(mod->loader_data(), leakp));
  return 1;
}

int write__module(JfrCheckpointWriter* writer, const void* m) {
  assert(m != NULL, "invariant");
  ModPtr mod = (ModPtr)m;
  set_serialized(mod);
  return write_module(writer, mod, false);
}

int write__module__leakp(JfrCheckpointWriter* writer, const void* m) {
  assert(m != NULL, "invariant");
  ModPtr mod = (ModPtr)m;
  CLEAR_LEAKP(mod);
  return write_module(writer, mod, true);
}

static void do_module(ModuleEntry* entry) {
  do_previous_epoch_artifact(_subsystem_callback, entry);
}

static void do_modules() {
  ClassLoaderDataGraph::modules_do(&do_module);
}

class ModuleFieldSelector {
 public:
  typedef ModPtr TypePtr;
  static TypePtr select(KlassPtr klass) {
    assert(klass != NULL, "invariant");
    PkgPtr pkg = klass->package();
    return pkg != NULL ? pkg->module() : NULL;
  }
};

typedef SerializePredicate<ModPtr> ModulePredicate;
typedef JfrPredicatedTypeWriterImplHost<ModPtr, ModulePredicate, write__module> ModuleWriterImpl;
typedef JfrTypeWriterHost<ModuleWriterImpl, TYPE_MODULE> ModuleWriter;
typedef CompositeFunctor<ModPtr, ModuleWriter, ClearArtifact<ModPtr> > ModuleWriterWithClear;
typedef JfrArtifactCallbackHost<ModPtr, ModuleWriterWithClear> ModuleCallback;
typedef KlassToFieldEnvelope<ModuleFieldSelector, ModuleWriter> KlassModuleWriter;

typedef LeakPredicate<ModPtr> LeakModulePredicate;
typedef JfrPredicatedTypeWriterImplHost<ModPtr, LeakModulePredicate, write__module__leakp> LeakModuleWriterImpl;
typedef JfrTypeWriterHost<LeakModuleWriterImpl, TYPE_MODULE> LeakModuleWriter;

typedef CompositeFunctor<ModPtr, LeakModuleWriter, ModuleWriter> CompositeModuleWriter;
typedef KlassToFieldEnvelope<ModuleFieldSelector, CompositeModuleWriter> KlassCompositeModuleWriter;
typedef CompositeFunctor<ModPtr, CompositeModuleWriter, ClearArtifact<ModPtr> > CompositeModuleWriterWithClear;
typedef JfrArtifactCallbackHost<ModPtr, CompositeModuleWriterWithClear> CompositeModuleCallback;

static void write_modules() {
  assert(_writer != NULL, "invariant");
  ModuleWriter mw(_writer, _class_unload);
  KlassModuleWriter kmw(&mw);
  if (current_epoch()) {
    _artifacts->iterate_klasses(kmw);
    _artifacts->tally(mw);
    return;
  }
  assert(previous_epoch(), "invariant");
  if (_leakp_writer == NULL) {
    _artifacts->iterate_klasses(kmw);
    ClearArtifact<ModPtr> clear;
    ModuleWriterWithClear mwwc(&mw, &clear);
    ModuleCallback callback(&mwwc);
    _subsystem_callback = &callback;
    do_modules();
  } else {
    LeakModuleWriter lmw(_leakp_writer, _class_unload);
    CompositeModuleWriter cmw(&lmw, &mw);
    KlassCompositeModuleWriter kcpw(&cmw);
    _artifacts->iterate_klasses(kcpw);
    ClearArtifact<ModPtr> clear;
    CompositeModuleWriterWithClear cmwwc(&cmw, &clear);
    CompositeModuleCallback callback(&cmwwc);
    _subsystem_callback = &callback;
    do_modules();
  }
  _artifacts->tally(mw);
}

static int write_classloader(JfrCheckpointWriter* writer, CldPtr cld, bool leakp) {
  assert(cld != NULL, "invariant");
  assert(!cld->is_anonymous(), "invariant");
  // class loader type
  const Klass* class_loader_klass = cld->class_loader_klass();
  if (class_loader_klass == NULL) {
    // (primordial) boot class loader
    writer->write(artifact_id(cld)); // class loader instance id
    writer->write((traceid)0);  // class loader type id (absence of)
    writer->write(get_bootstrap_name(leakp)); // maps to synthetic name -> "bootstrap"
  } else {
    writer->write(artifact_id(cld)); // class loader instance id
    writer->write(artifact_id(class_loader_klass)); // class loader type id
    writer->write(mark_symbol(cld->name(), leakp)); // class loader instance name
  }
  return 1;
}

int write__classloader(JfrCheckpointWriter* writer, const void* c) {
  assert(c != NULL, "invariant");
  CldPtr cld = (CldPtr)c;
  set_serialized(cld);
  return write_classloader(writer, cld, false);
}

int write__classloader__leakp(JfrCheckpointWriter* writer, const void* c) {
  assert(c != NULL, "invariant");
  CldPtr cld = (CldPtr)c;
  CLEAR_LEAKP(cld);
  return write_classloader(writer, cld, true);
}

static void do_class_loader_data(ClassLoaderData* cld) {
  do_previous_epoch_artifact(_subsystem_callback, cld);
}

class KlassCldFieldSelector {
 public:
  typedef CldPtr TypePtr;
  static TypePtr select(KlassPtr klass) {
    assert(klass != NULL, "invariant");
    return get_cld(klass);
  }
};

class ModuleCldFieldSelector {
public:
  typedef CldPtr TypePtr;
  static TypePtr select(KlassPtr klass) {
    assert(klass != NULL, "invariant");
    ModPtr mod = ModuleFieldSelector::select(klass);
    return mod != NULL ? mod->loader_data() : NULL;
  }
};

class CLDCallback : public CLDClosure {
 public:
  CLDCallback() {}
  void do_cld(ClassLoaderData* cld) {
    assert(cld != NULL, "invariant");
    if (cld->is_anonymous()) {
      return;
    }
    do_class_loader_data(cld);
  }
};

static void do_class_loaders() {
  CLDCallback cld_cb;
  ClassLoaderDataGraph::cld_do(&cld_cb);
}

typedef SerializePredicate<CldPtr> CldPredicate;
typedef JfrPredicatedTypeWriterImplHost<CldPtr, CldPredicate, write__classloader> CldWriterImpl;
typedef JfrTypeWriterHost<CldWriterImpl, TYPE_CLASSLOADER> CldWriter;
typedef CompositeFunctor<CldPtr, CldWriter, ClearArtifact<CldPtr> > CldWriterWithClear;
typedef JfrArtifactCallbackHost<CldPtr, CldWriterWithClear> CldCallback;
typedef KlassToFieldEnvelope<KlassCldFieldSelector, CldWriter> KlassCldWriter;
typedef KlassToFieldEnvelope<ModuleCldFieldSelector, CldWriter> ModuleCldWriter;
typedef CompositeFunctor<KlassPtr, KlassCldWriter, ModuleCldWriter> KlassAndModuleCldWriter;

typedef LeakPredicate<CldPtr> LeakCldPredicate;
typedef JfrPredicatedTypeWriterImplHost<CldPtr, LeakCldPredicate, write__classloader__leakp> LeakCldWriterImpl;
typedef JfrTypeWriterHost<LeakCldWriterImpl, TYPE_CLASSLOADER> LeakCldWriter;

typedef CompositeFunctor<CldPtr, LeakCldWriter, CldWriter> CompositeCldWriter;
typedef KlassToFieldEnvelope<KlassCldFieldSelector, CompositeCldWriter> KlassCompositeCldWriter;
typedef KlassToFieldEnvelope<ModuleCldFieldSelector, CompositeCldWriter> ModuleCompositeCldWriter;
typedef CompositeFunctor<KlassPtr, KlassCompositeCldWriter, ModuleCompositeCldWriter> KlassAndModuleCompositeCldWriter;
typedef CompositeFunctor<CldPtr, CompositeCldWriter, ClearArtifact<CldPtr> > CompositeCldWriterWithClear;
typedef JfrArtifactCallbackHost<CldPtr, CompositeCldWriterWithClear> CompositeCldCallback;

static void write_classloaders() {
  assert(_writer != NULL, "invariant");
  CldWriter cldw(_writer, _class_unload);
  KlassCldWriter kcw(&cldw);
  ModuleCldWriter mcw(&cldw);
  KlassAndModuleCldWriter kmcw(&kcw, &mcw);
  if (current_epoch()) {
    _artifacts->iterate_klasses(kmcw);
    _artifacts->tally(cldw);
    return;
  }
  assert(previous_epoch(), "invariant");
  if (_leakp_writer == NULL) {
    _artifacts->iterate_klasses(kmcw);
    ClearArtifact<CldPtr> clear;
    CldWriterWithClear cldwwc(&cldw, &clear);
    CldCallback callback(&cldwwc);
    _subsystem_callback = &callback;
    do_class_loaders();
  } else {
    LeakCldWriter lcldw(_leakp_writer, _class_unload);
    CompositeCldWriter ccldw(&lcldw, &cldw);
    KlassCompositeCldWriter kccldw(&ccldw);
    ModuleCompositeCldWriter mccldw(&ccldw);
    KlassAndModuleCompositeCldWriter kmccldw(&kccldw, &mccldw);
    _artifacts->iterate_klasses(kmccldw);
    ClearArtifact<CldPtr> clear;
    CompositeCldWriterWithClear ccldwwc(&ccldw, &clear);
    CompositeCldCallback callback(&ccldwwc);
    _subsystem_callback = &callback;
    do_class_loaders();
  }
  _artifacts->tally(cldw);
}

static u1 get_visibility(MethodPtr method) {
  assert(method != NULL, "invariant");
  return const_cast<Method*>(method)->is_hidden() ? (u1)1 : (u1)0;
}

template <>
void set_serialized<Method>(MethodPtr method) {
  assert(method != NULL, "invariant");
  SET_METHOD_SERIALIZED(method);
  assert(IS_METHOD_SERIALIZED(method), "invariant");
}

static int write_method(JfrCheckpointWriter* writer, MethodPtr method, bool leakp) {
  assert(writer != NULL, "invariant");
  assert(method != NULL, "invariant");
  assert(_artifacts != NULL, "invariant");
  KlassPtr klass = method->method_holder();
  assert(klass != NULL, "invariant");
  writer->write(method_id(klass, method));
  writer->write(artifact_id(klass));
  writer->write(mark_symbol(method->name(), leakp));
  writer->write(mark_symbol(method->signature(), leakp));
  writer->write((u2)get_flags(method));
  writer->write(get_visibility(method));
  return 1;
}

int write__method(JfrCheckpointWriter* writer, const void* m) {
  assert(m != NULL, "invariant");
  MethodPtr method = (MethodPtr)m;
  set_serialized(method);
  return write_method(writer, method, false);
}

int write__method__leakp(JfrCheckpointWriter* writer, const void* m) {
  assert(m != NULL, "invariant");
  MethodPtr method = (MethodPtr)m;
  return write_method(writer, method, true);
}

class BitMapFilter {
  ResourceBitMap _bitmap;
 public:
  explicit BitMapFilter(int length = 0) : _bitmap((size_t)length) {}
  bool operator()(size_t idx) {
    if (_bitmap.size() == 0) {
      return true;
    }
    if (_bitmap.at(idx)) {
      return false;
    }
    _bitmap.set_bit(idx);
    return true;
  }
};

class AlwaysTrue {
 public:
  explicit AlwaysTrue(int length = 0) {}
  bool operator()(size_t idx) {
    return true;
  }
};

template <typename MethodCallback, typename KlassCallback, class Filter, bool leakp>
class MethodIteratorHost {
 private:
  MethodCallback _method_cb;
  KlassCallback _klass_cb;
  MethodUsedPredicate<leakp> _method_used_predicate;
  MethodFlagPredicate<leakp> _method_flag_predicate;
 public:
  MethodIteratorHost(JfrCheckpointWriter* writer,
                     bool current_epoch = false,
                     bool class_unload = false,
                     bool skip_header = false) :
    _method_cb(writer, class_unload, skip_header),
    _klass_cb(writer, class_unload, skip_header),
    _method_used_predicate(current_epoch),
    _method_flag_predicate(current_epoch) {}

  bool operator()(KlassPtr klass) {
    if (_method_used_predicate(klass)) {
      const InstanceKlass* ik = InstanceKlass::cast(klass);
      while (ik != NULL) {
        const int len = ik->methods()->length();
        for (int i = 0; i < len; ++i) {
          MethodPtr method = ik->methods()->at(i);
          if (_method_flag_predicate(method)) {
            _method_cb(method);
          }
        }
        // There can be multiple versions of the same method running
        // due to redefinition. Need to inspect the complete set of methods.
        ik = ik->previous_versions();
      }
    }
    return _klass_cb(klass);
  }

  int count() const { return _method_cb.count(); }
  void add(int count) { _method_cb.add(count); }
};

template <typename T, template <typename> class Impl>
class Wrapper {
  Impl<T> _t;
 public:
  Wrapper(JfrCheckpointWriter*, bool, bool) : _t() {}
  bool operator()(T const& value) {
    return _t(value);
  }
};

template <typename T>
class EmptyStub {
 public:
  bool operator()(T const& value) { return true; }
};

typedef SerializePredicate<MethodPtr> MethodPredicate;
typedef JfrPredicatedTypeWriterImplHost<MethodPtr, MethodPredicate, write__method> MethodWriterImplTarget;
typedef Wrapper<KlassPtr, EmptyStub> KlassCallbackStub;
typedef JfrTypeWriterHost<MethodWriterImplTarget, TYPE_METHOD> MethodWriterImpl;
typedef MethodIteratorHost<MethodWriterImpl, KlassCallbackStub, BitMapFilter, false> MethodWriter;

typedef LeakPredicate<MethodPtr> LeakMethodPredicate;
typedef JfrPredicatedTypeWriterImplHost<MethodPtr, LeakMethodPredicate, write__method__leakp> LeakMethodWriterImplTarget;
typedef JfrTypeWriterHost<LeakMethodWriterImplTarget, TYPE_METHOD> LeakMethodWriterImpl;
typedef MethodIteratorHost<LeakMethodWriterImpl, KlassCallbackStub, BitMapFilter, true> LeakMethodWriter;
typedef MethodIteratorHost<LeakMethodWriterImpl, KlassCallbackStub, BitMapFilter, true> LeakMethodWriter;
typedef CompositeFunctor<KlassPtr, LeakMethodWriter, MethodWriter> CompositeMethodWriter;

static void write_methods() {
  assert(_writer != NULL, "invariant");
  MethodWriter mw(_writer, current_epoch(), _class_unload);
  if (_leakp_writer == NULL) {
    _artifacts->iterate_klasses(mw);
  } else {
    LeakMethodWriter lpmw(_leakp_writer, current_epoch(), _class_unload);
    CompositeMethodWriter cmw(&lpmw, &mw);
    _artifacts->iterate_klasses(cmw);
  }
  _artifacts->tally(mw);
}

template <>
void set_serialized<JfrSymbolId::SymbolEntry>(SymbolEntryPtr ptr) {
  assert(ptr != NULL, "invariant");
  ptr->set_serialized();
  assert(ptr->is_serialized(), "invariant");
}

template <>
void set_serialized<JfrSymbolId::CStringEntry>(CStringEntryPtr ptr) {
  assert(ptr != NULL, "invariant");
  ptr->set_serialized();
  assert(ptr->is_serialized(), "invariant");
}

static int write_symbol(JfrCheckpointWriter* writer, SymbolEntryPtr entry, bool leakp) {
  assert(writer != NULL, "invariant");
  assert(entry != NULL, "invariant");
  ResourceMark rm;
  writer->write(create_symbol_id(entry->id()));
  writer->write(entry->value()->as_C_string());
  return 1;
}

int write__symbol(JfrCheckpointWriter* writer, const void* e) {
  assert(e != NULL, "invariant");
  SymbolEntryPtr entry = (SymbolEntryPtr)e;
  set_serialized(entry);
  return write_symbol(writer, entry, false);
}

int write__symbol__leakp(JfrCheckpointWriter* writer, const void* e) {
  assert(e != NULL, "invariant");
  SymbolEntryPtr entry = (SymbolEntryPtr)e;
  return write_symbol(writer, entry, true);
}

static int write_cstring(JfrCheckpointWriter* writer, CStringEntryPtr entry, bool leakp) {
  assert(writer != NULL, "invariant");
  assert(entry != NULL, "invariant");
  writer->write(create_symbol_id(entry->id()));
  writer->write(entry->value());
  return 1;
}

int write__cstring(JfrCheckpointWriter* writer, const void* e) {
  assert(e != NULL, "invariant");
  CStringEntryPtr entry = (CStringEntryPtr)e;
  set_serialized(entry);
  return write_cstring(writer, entry, false);
}

int write__cstring__leakp(JfrCheckpointWriter* writer, const void* e) {
  assert(e != NULL, "invariant");
  CStringEntryPtr entry = (CStringEntryPtr)e;
  return write_cstring(writer, entry, true);
}

typedef SymbolPredicate<SymbolEntryPtr, false> SymPredicate;
typedef JfrPredicatedTypeWriterImplHost<SymbolEntryPtr, SymPredicate, write__symbol> SymbolEntryWriterImpl;
typedef JfrTypeWriterHost<SymbolEntryWriterImpl, TYPE_SYMBOL> SymbolEntryWriter;
typedef SymbolPredicate<CStringEntryPtr, false> CStringPredicate;
typedef JfrPredicatedTypeWriterImplHost<CStringEntryPtr, CStringPredicate, write__cstring> CStringEntryWriterImpl;
typedef JfrTypeWriterHost<CStringEntryWriterImpl, TYPE_SYMBOL> CStringEntryWriter;

typedef SymbolPredicate<SymbolEntryPtr, true> LeakSymPredicate;
typedef JfrPredicatedTypeWriterImplHost<SymbolEntryPtr, LeakSymPredicate, write__symbol__leakp> LeakSymbolEntryWriterImpl;
typedef JfrTypeWriterHost<LeakSymbolEntryWriterImpl, TYPE_SYMBOL> LeakSymbolEntryWriter;
typedef CompositeFunctor<SymbolEntryPtr, LeakSymbolEntryWriter, SymbolEntryWriter> CompositeSymbolWriter;
typedef SymbolPredicate<CStringEntryPtr, true> LeakCStringPredicate;
typedef JfrPredicatedTypeWriterImplHost<CStringEntryPtr, LeakCStringPredicate, write__cstring__leakp> LeakCStringEntryWriterImpl;
typedef JfrTypeWriterHost<LeakCStringEntryWriterImpl, TYPE_SYMBOL> LeakCStringEntryWriter;
typedef CompositeFunctor<CStringEntryPtr, LeakCStringEntryWriter, CStringEntryWriter> CompositeCStringWriter;

static void write_symbols_with_leakp() {
  assert(_leakp_writer != NULL, "invariant");
  SymbolEntryWriter sw(_writer, _class_unload);
  LeakSymbolEntryWriter lsw(_leakp_writer, _class_unload);
  CompositeSymbolWriter csw(&lsw, &sw);
  _artifacts->iterate_symbols(csw);
  CStringEntryWriter ccsw(_writer, _class_unload, true); // skip header
  LeakCStringEntryWriter lccsw(_leakp_writer, _class_unload, true); // skip header
  CompositeCStringWriter cccsw(&lccsw, &ccsw);
  _artifacts->iterate_cstrings(cccsw);
  sw.add(ccsw.count());
  lsw.add(lccsw.count());
  _artifacts->tally(sw);
}

static void write_symbols() {
  assert(_writer != NULL, "invariant");
  if (_leakp_writer != NULL) {
    write_symbols_with_leakp();
    return;
  }
  SymbolEntryWriter sw(_writer, _class_unload);
  _artifacts->iterate_symbols(sw);
  CStringEntryWriter csw(_writer, _class_unload, true); // skip header
  _artifacts->iterate_cstrings(csw);
  sw.add(csw.count());
  _artifacts->tally(sw);
}

void JfrTypeSet::clear() {
  _clear_artifacts = true;
}

typedef Wrapper<KlassPtr, ClearArtifact> ClearKlassBits;
typedef Wrapper<MethodPtr, ClearArtifact> ClearMethodFlag;
typedef MethodIteratorHost<ClearMethodFlag, ClearKlassBits, AlwaysTrue, false> ClearKlassAndMethods;

static size_t teardown() {
  assert(_artifacts != NULL, "invariant");
  const size_t total_count = _artifacts->total_count();
  if (previous_epoch()) {
    assert(_writer != NULL, "invariant");
    ClearKlassAndMethods clear(_writer);
    _artifacts->iterate_klasses(clear);
    _clear_artifacts = true;
    ++checkpoint_id;
  } else {
    _clear_artifacts = false;
  }
  return total_count;
}

static void setup(JfrCheckpointWriter* writer, JfrCheckpointWriter* leakp_writer, bool class_unload) {
  _writer = writer;
  _leakp_writer = leakp_writer;
  _class_unload = class_unload;
  if (_artifacts == NULL) {
    _artifacts = new JfrArtifactSet(class_unload);
  } else {
    _artifacts->initialize(class_unload, _clear_artifacts);
  }
  assert(_artifacts != NULL, "invariant");
  assert(!_artifacts->has_klass_entries(), "invariant");
}

/**
 * Write all "tagged" (in-use) constant artifacts and their dependencies.
 */
size_t JfrTypeSet::serialize(JfrCheckpointWriter* writer, JfrCheckpointWriter* leakp_writer, bool class_unload) {
  assert(writer != NULL, "invariant");
  ResourceMark rm;
  setup(writer, leakp_writer, class_unload);
  // write order is important because an individual write step
  // might tag an artifact to be written in a subsequent step
  if (!write_klasses()) {
    return 0;
  }
  write_packages();
  write_modules();
  write_classloaders();
  write_methods();
  write_symbols();
  return teardown();
}
