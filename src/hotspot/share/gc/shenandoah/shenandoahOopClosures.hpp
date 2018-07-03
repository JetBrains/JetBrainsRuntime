/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHOOPCLOSURES_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHOOPCLOSURES_HPP

#include "gc/shenandoah/shenandoahTaskqueue.hpp"

class ShenandoahHeap;
class ShenandoahStrDedupQueue;

enum UpdateRefsMode {
  NONE,       // No reference updating
  RESOLVE,    // Only a read-barrier (no reference updating)
  SIMPLE,     // Reference updating using simple store
  CONCURRENT  // Reference updating using CAS
};

class ShenandoahMarkRefsSuperClosure : public MetadataVisitingOopIterateClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahHeap* _heap;

protected:
  template <class T, UpdateRefsMode UPDATE_MODE, bool STRING_DEDUP>
  void work(T *p);

public:
  ShenandoahMarkRefsSuperClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp);

  inline void set_base_object(oop obj) { /* Not needed */ }
};

class ShenandoahMarkUpdateRefsClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, CONCURRENT, false /* string dedup */>(p); }

public:
  ShenandoahMarkUpdateRefsClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
          ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkUpdateRefsDedupClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, CONCURRENT, true /* string dedup */>(p); }

public:
  ShenandoahMarkUpdateRefsDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
          ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkUpdateRefsMetadataClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, CONCURRENT, false /* string dedup */>(p); }

public:
  ShenandoahMarkUpdateRefsMetadataClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return true; }
};

class ShenandoahMarkUpdateRefsMetadataDedupClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, CONCURRENT, true /* string dedup */>(p); }

public:
  ShenandoahMarkUpdateRefsMetadataDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
  ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return true; }
};

class ShenandoahMarkRefsClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, NONE, false /* string dedup */>(p); }

public:
  ShenandoahMarkRefsClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkRefsDedupClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, NONE, true /* string dedup */>(p); }

public:
  ShenandoahMarkRefsDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkResolveRefsClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, RESOLVE, false /* string dedup */>(p); }

public:
  ShenandoahMarkResolveRefsClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkResolveRefsDedupClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, RESOLVE, true /* string dedup */>(p); }

public:
  ShenandoahMarkResolveRefsDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return false; }
};

class ShenandoahMarkRefsMetadataClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, NONE, false /* string dedup */>(p); }

public:
  ShenandoahMarkRefsMetadataClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return true; }
};

class ShenandoahMarkRefsMetadataDedupClosure : public ShenandoahMarkRefsSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, NONE, true /* string dedup */>(p); }

public:
  ShenandoahMarkRefsMetadataDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahMarkRefsSuperClosure(q, rp) {};

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual bool do_metadata()        { return true; }
};

class ShenandoahUpdateHeapRefsSuperClosure : public BasicOopIterateClosure {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahUpdateHeapRefsSuperClosure() :
    _heap(ShenandoahHeap::heap()) {}

  template <class T, bool UPDATE_MATRIX>
  void work(T *p);
};

class ShenandoahUpdateHeapRefsClosure : public ShenandoahUpdateHeapRefsSuperClosure {
private:
  template <class T>
  inline  void do_oop_work(T* p)    { work<T, false>(p); }

public:
  ShenandoahUpdateHeapRefsClosure() : ShenandoahUpdateHeapRefsSuperClosure() {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
};

class ShenandoahUpdateHeapRefsMatrixClosure : public ShenandoahUpdateHeapRefsSuperClosure {
public:
  ShenandoahUpdateHeapRefsMatrixClosure() : ShenandoahUpdateHeapRefsSuperClosure() {}

  template <class T>
  inline  void do_oop_work(T* p)    { work<T, true>(p); }
  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
};

class ShenandoahTraversalSuperClosure : public MetadataVisitingOopIterateClosure {
private:
  ShenandoahTraversalGC* _traversal_gc;
  Thread* _thread;
  ShenandoahObjToScanQueue* _queue;
  oop _base_obj;
protected:
  ShenandoahTraversalSuperClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    MetadataVisitingOopIterateClosure(rp),
    _traversal_gc(ShenandoahHeap::heap()->traversal_gc()),
    _thread(Thread::current()), _queue(q), _base_obj(NULL) {
  }

  template <class T, bool STRING_DEDUP, bool DEGEN, bool MATRIX>
  void work(T* p);

public:
  inline void set_base_object(oop obj) {
    _base_obj = obj;
  }
};

class ShenandoahTraversalClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, false, false>(p); }

public:
  ShenandoahTraversalClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, false, false>(p); }

public:
  ShenandoahTraversalMetadataClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDedupClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, false, false>(p); }

public:
  ShenandoahTraversalDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDedupClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, false, false>(p); }

public:
  ShenandoahTraversalMetadataDedupClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDegenClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, true, false>(p); }

public:
  ShenandoahTraversalDegenClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDegenClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, true, false>(p); }

public:
  ShenandoahTraversalMetadataDegenClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDedupDegenClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, true, false>(p); }

public:
  ShenandoahTraversalDedupDegenClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDedupDegenClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, true, false>(p); }

public:
  ShenandoahTraversalMetadataDedupDegenClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, false, true>(p); }

public:
  ShenandoahTraversalMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, false, true>(p); }

public:
  ShenandoahTraversalMetadataMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDedupMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, false, true>(p); }

public:
  ShenandoahTraversalDedupMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDedupMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, false, true>(p); }

public:
  ShenandoahTraversalMetadataDedupMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDegenMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, true, true>(p); }

public:
  ShenandoahTraversalDegenMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDegenMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, false, true, true>(p); }

public:
  ShenandoahTraversalMetadataDegenMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

class ShenandoahTraversalDedupDegenMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, true, true>(p); }

public:
  ShenandoahTraversalDedupDegenMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return false; }
};

class ShenandoahTraversalMetadataDedupDegenMatrixClosure : public ShenandoahTraversalSuperClosure {
private:
  template <class T>
  inline void do_oop_work(T* p)     { work<T, true, true, true>(p); }

public:
  ShenandoahTraversalMetadataDedupDegenMatrixClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
    ShenandoahTraversalSuperClosure(q, rp) {}

  virtual void do_oop(narrowOop* p) { do_oop_work(p); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }

  virtual bool do_metadata()        { return true; }
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHOOPCLOSURES_HPP
