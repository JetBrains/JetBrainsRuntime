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

#ifndef SHARE_VM_OPTO_SHENANDOAH_SUPPORT_HPP
#define SHARE_VM_OPTO_SHENANDOAH_SUPPORT_HPP

#include "gc/shenandoah/brooksPointer.hpp"
#include "memory/allocation.hpp"
#include "opto/addnode.hpp"
#include "opto/graphKit.hpp"
#include "opto/machnode.hpp"
#include "opto/memnode.hpp"
#include "opto/multnode.hpp"
#include "opto/node.hpp"

class PhaseGVN;


class ShenandoahBarrierNode : public TypeNode {
private:
  bool _allow_fromspace;

#ifdef ASSERT
  enum verify_type {
    ShenandoahLoad,
    ShenandoahStore,
    ShenandoahValue,
    ShenandoahNone,
  };

  static bool verify_helper(Node* in, Node_Stack& phis, VectorSet& visited, verify_type t, bool trace, Unique_Node_List& barriers_used);
#endif

public:

public:
  enum { Control,
         Memory,
         ValueIn
  };

  ShenandoahBarrierNode(Node* ctrl, Node* mem, Node* obj, bool allow_fromspace)
    : TypeNode(obj->bottom_type()->isa_oopptr() ? obj->bottom_type()->is_oopptr()->cast_to_nonconst() : obj->bottom_type(), 3),
      _allow_fromspace(allow_fromspace) {

    init_req(Control, ctrl);
    init_req(Memory, mem);
    init_req(ValueIn, obj);

    init_class_id(Class_ShenandoahBarrier);
  }

  static Node* skip_through_barrier(Node* n);

  static const TypeOopPtr* brooks_pointer_type(const Type* t) {
    return t->is_oopptr()->cast_to_nonconst()->add_offset(BrooksPointer::byte_offset())->is_oopptr();
  }

  virtual const TypePtr* adr_type() const {
    if (bottom_type() == Type::TOP) {
      return NULL;
    }
    //const TypePtr* adr_type = in(MemNode::Address)->bottom_type()->is_ptr();
    const TypePtr* adr_type = brooks_pointer_type(bottom_type());
    assert(adr_type->offset() == BrooksPointer::byte_offset(), "sane offset");
    assert(Compile::current()->alias_type(adr_type)->is_rewritable(), "brooks ptr must be rewritable");
    return adr_type;
  }

  virtual uint  ideal_reg() const { return Op_RegP; }
  virtual uint match_edge(uint idx) const {
    return idx >= ValueIn;
  }

  Node* Identity_impl(PhaseGVN* phase);

  virtual const Type* Value(PhaseGVN* phase) const;
  virtual bool depends_only_on_test() const {
    return true;
  };

  static bool needs_barrier(PhaseGVN* phase, ShenandoahBarrierNode* orig, Node* n, Node* rb_mem, bool allow_fromspace);

#ifdef ASSERT
  static void report_verify_failure(const char* msg, Node* n1 = NULL, Node* n2 = NULL);
  static void verify(RootNode* root);
  static void verify_raw_mem(RootNode* root);
#endif
#ifndef PRODUCT
  virtual void dump_spec(outputStream *st) const;
#endif

  Node* try_common(Node *n_ctrl, PhaseIdealLoop* phase);

  // protected:
  static Node* dom_mem(Node* mem, Node*& mem_ctrl, Node* n, Node* rep_ctrl, int alias, PhaseIdealLoop* phase);
  static Node* dom_mem(Node* mem, Node* ctrl, int alias, Node*& mem_ctrl, PhaseIdealLoop* phase);
  static bool is_dominator(Node *d_c, Node *n_c, Node* d, Node* n, PhaseIdealLoop* phase);
  static bool is_dominator_same_ctrl(Node* c, Node* d, Node* n, PhaseIdealLoop* phase);
  static Node* no_branches(Node* c, Node* dom, bool allow_one_proj, PhaseIdealLoop* phase);
  static void do_cmpp_if(GraphKit& kit, Node*& taken_branch, Node*& untaken_branch, Node*& taken_memory, Node*& untaken_memory);

protected:
  uint hash() const;
  uint cmp(const Node& n) const;
  uint size_of() const;

private:
  static bool needs_barrier_impl(PhaseGVN* phase, ShenandoahBarrierNode* orig, Node* n, Node* rb_mem, bool allow_fromspace, Unique_Node_List &visited);


  static bool dominates_memory(PhaseGVN* phase, Node* b1, Node* b2, bool linear);
  static bool dominates_memory_impl(PhaseGVN* phase, Node* b1, Node* b2, Node* current, bool linear);
};

class ShenandoahReadBarrierNode : public ShenandoahBarrierNode {
public:
  ShenandoahReadBarrierNode(Node* ctrl, Node* mem, Node* obj)
    : ShenandoahBarrierNode(ctrl, mem, obj, true) {
    assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier ||
                               ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier ||
                               ShenandoahAcmpBarrier),
           "should be enabled");
  }
  ShenandoahReadBarrierNode(Node* ctrl, Node* mem, Node* obj, bool allow_fromspace)
    : ShenandoahBarrierNode(ctrl, mem, obj, allow_fromspace) {
    assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier ||
                               ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier ||
                               ShenandoahAcmpBarrier),
           "should be enabled");
  }

  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual int Opcode() const;

  bool is_independent(Node* mem);

  void try_move(Node *n_ctrl, PhaseIdealLoop* phase);

private:
  static bool is_independent(const Type* in_type, const Type* this_type);
  static bool dominates_memory_rb(PhaseGVN* phase, Node* b1, Node* b2, bool linear);
  static bool dominates_memory_rb_impl(PhaseGVN* phase, Node* b1, Node* b2, Node* current, bool linear);
};

class ShenandoahWriteBarrierNode : public ShenandoahBarrierNode {
private:
  static bool fix_mem_phis_helper(Node* c, Node* mem, Node* mem_ctrl, Node* rep_ctrl, int alias, VectorSet& controls, GrowableArray<Node*>& phis, PhaseIdealLoop* phase);

public:
  ShenandoahWriteBarrierNode(Compile* C, Node* ctrl, Node* mem, Node* obj)
    : ShenandoahBarrierNode(ctrl, mem, obj, false) {
    assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier), "should be enabled");
    C->add_shenandoah_barrier(this);
  }

  virtual int Opcode() const;
  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual bool depends_only_on_test() const { return false; }

  static bool expand(Compile* C, PhaseIterGVN& igvn, int& loop_opts_cnt);
  static bool is_evacuation_in_progress_test(Node* iff);
  static Node* evacuation_in_progress_test_ctrl(Node* iff);

  static LoopNode* try_move_before_pre_loop(Node* c, Node* val_ctrl, PhaseIdealLoop* phase);
  static Node* move_above_predicates(LoopNode* cl, Node* val_ctrl, PhaseIdealLoop* phase);
  static bool fix_mem_phis(Node* mem, Node* mem_ctrl, Node* rep_ctrl, int alias, PhaseIdealLoop* phase);
  static bool should_process_phi(Node* phi, int alias, Compile* C);
  static void fix_memory_uses(Node* mem, Node* replacement, Node* rep_proj, Node* rep_ctrl, int alias, PhaseIdealLoop* phase);
  static MergeMemNode* allocate_merge_mem(Node* mem, int alias, Node* rep_proj, Node* rep_ctrl, PhaseIdealLoop* phase);
  static MergeMemNode* clone_merge_mem(Node* u, Node* mem, int alias, Node* rep_proj, Node* rep_ctrl, DUIterator& i, PhaseIdealLoop* phase);
#ifdef ASSERT
  static bool memory_dominates_all_paths(Node* mem, Node* rep_ctrl, int alias, PhaseIdealLoop* phase);
  static void memory_dominates_all_paths_helper(Node* c, Node* rep_ctrl, Unique_Node_List& controls, PhaseIdealLoop* phase);
#endif
  Node* try_move_before_loop(Node *n_ctrl, PhaseIdealLoop* phase);
  Node* try_move_before_loop_helper(LoopNode* cl,  Node* val_ctrl, Node* mem, PhaseIdealLoop* phase);
  static void pin_and_expand(PhaseIdealLoop* phase);
  CallStaticJavaNode* pin_and_expand_null_check(PhaseIterGVN& igvn);
  void pin_and_expand_move_barrier(PhaseIdealLoop* phase);
  void pin_and_expand_helper(PhaseIdealLoop* phase);
  static Node* find_raw_mem(Node* ctrl, Node* wb, const Node_List& memory_nodes, PhaseIdealLoop* phase);
  static Node* pick_phi(Node* phi1, Node* phi2, Node_Stack& phis, VectorSet& visited, PhaseIdealLoop* phase);
  static Node* find_bottom_mem(Node* ctrl, PhaseIdealLoop* phase);
  static void follow_barrier_uses(Node* n, Node* ctrl, Unique_Node_List& uses, PhaseIdealLoop* phase);
  static void collect_memory_nodes(int alias, Node_List& memory_nodes, PhaseIdealLoop* phase);
  static void fix_raw_mem(Node* ctrl, Node* region, Node* raw_mem, Node* raw_mem_for_ctrl,
                          Node* raw_mem_phi, Node_List& memory_nodes,
                          Unique_Node_List& uses,
                          PhaseIdealLoop* phase);
  static void test_evacuation_in_progress(Node* ctrl, int alias, Node*& raw_mem, Node*& wb_mem,
                                          IfNode*& evacuation_iff, Node*& evac_in_progress,
                                          Node*& evac_not_in_progress, PhaseIdealLoop* phase);
  static void evacuation_not_in_progress(Node* c, Node* v, Node* unc_ctrl, Node* raw_mem, Node* wb_mem, Node* region,
                                         Node* val_phi, Node* mem_phi, Node* raw_mem_phi, Node*& unc_region,
                                         PhaseIdealLoop* phase);
  static void evacuation_in_progress(Node* c, Node* val, Node* evacuation_iff, Node* unc, Node* unc_ctrl,
                                     Node* raw_mem, Node* wb_mem, Node* region, Node* val_phi, Node* mem_phi,
                                     Node* raw_mem_phi, Node* unc_region, int alias, Unique_Node_List& uses,
                                     PhaseIdealLoop* phase);
  static void evacuation_not_in_progress_null_check(Node*& c, Node*& val, Node* unc_ctrl, Node*& unc_region,
                                                    PhaseIdealLoop* phase);
  static void evacuation_in_progress_null_check(Node*& c, Node*& val, Node* evacuation_iff, Node* unc, Node* unc_ctrl,
                                                Node* unc_region, Unique_Node_List& uses, PhaseIdealLoop* phase);
  static void in_cset_fast_test(Node*& c, Node* rbtrue, Node* raw_mem, Node* wb_mem, Node* region, Node* val_phi,
                                Node* mem_phi, Node* raw_mem_phi, PhaseIdealLoop* phase);
  static Node* get_ctrl(Node* n, PhaseIdealLoop* phase);
  static Node* ctrl_or_self(Node* n, PhaseIdealLoop* phase);
  static bool mem_is_valid(Node* m, Node* c, PhaseIdealLoop* phase);
  static void backtoback_evacs(IfNode* iff, IfNode* dom_if, PhaseIdealLoop* phase);
  static void move_evacuation_test_out_of_loop(IfNode* iff, PhaseIdealLoop* phase);
  static void optimize_after_expansion(const Node_List& evacuation_tests, Node_List &old_new, PhaseIdealLoop* phase);
  static void merge_back_to_back_evacuation_tests(Node* n, PhaseIdealLoop* phase);
};

class ShenandoahWBMemProjNode : public ProjNode {
public:
  enum {SWBMEMPROJCON = (uint)-3};
  ShenandoahWBMemProjNode(Node *src) : ProjNode( src, SWBMEMPROJCON) {
    assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier), "should be enabled");
    assert(src->Opcode() == Op_ShenandoahWriteBarrier || src->is_Mach(), "epxect wb");
  }
  virtual Node* Identity(PhaseGVN* phase);

  virtual int Opcode() const;
  virtual bool      is_CFG() const  { return false; }
  virtual const Type *bottom_type() const {return Type::MEMORY;}
  virtual const TypePtr *adr_type() const {
    Node* wb = in(0);
    if (wb == NULL || wb->is_top())  return NULL; // node is dead
    assert(wb->Opcode() == Op_ShenandoahWriteBarrier || (wb->is_Mach() && wb->as_Mach()->ideal_Opcode() == Op_ShenandoahWriteBarrier), "expect wb");
    return ShenandoahBarrierNode::brooks_pointer_type(wb->bottom_type());
  }

  virtual uint ideal_reg() const { return 0;} // memory projections don't have a register
  virtual const Type *Value(PhaseGVN* phase ) const {
    return bottom_type();
  }
#ifndef PRODUCT
  virtual void dump_spec(outputStream *st) const {};
#endif
};

#endif // SHARE_VM_OPTO_SHENANDOAH_SUPPORT_HPP
