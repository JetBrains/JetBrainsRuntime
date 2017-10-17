/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
#include "opto/addnode.hpp"
#include "opto/cfgnode.hpp"
#include "opto/connode.hpp"
#include "opto/divnode.hpp"
#include "opto/loopnode.hpp"
#include "opto/opaquenode.hpp"
#include "opto/phaseX.hpp"

//=============================================================================
// Do not allow value-numbering
uint Opaque1Node::hash() const { return NO_HASH; }
uint Opaque1Node::cmp( const Node &n ) const {
  return (&n == this);          // Always fail except on self
}

//------------------------------Identity---------------------------------------
// If _major_progress, then more loop optimizations follow.  Do NOT remove
// the opaque Node until no more loop ops can happen.  Note the timing of
// _major_progress; it's set in the major loop optimizations THEN comes the
// call to IterGVN and any chance of hitting this code.  Hence there's no
// phase-ordering problem with stripping Opaque1 in IGVN followed by some
// more loop optimizations that require it.
Node* Opaque1Node::Identity(PhaseGVN* phase) {
  return phase->C->major_progress() ? this : in(1);
}

//=============================================================================
// A node to prevent unwanted optimizations.  Allows constant folding.  Stops
// value-numbering, most Ideal calls or Identity functions.  This Node is
// specifically designed to prevent the pre-increment value of a loop trip
// counter from being live out of the bottom of the loop (hence causing the
// pre- and post-increment values both being live and thus requiring an extra
// temp register and an extra move).  If we "accidentally" optimize through
// this kind of a Node, we'll get slightly pessimal, but correct, code.  Thus
// it's OK to be slightly sloppy on optimizations here.

// Do not allow value-numbering
uint Opaque2Node::hash() const { return NO_HASH; }
uint Opaque2Node::cmp( const Node &n ) const {
  return (&n == this);          // Always fail except on self
}

Node* Opaque4Node::Identity(PhaseGVN* phase) {
  return phase->C->major_progress() ? this : in(2);
}

const Type* Opaque4Node::Value(PhaseGVN* phase) const {
  return phase->type(in(1));
}

Node* Opaque5Node::adjust_strip_mined_loop(PhaseGVN* phase) {
  // Look for the outer & inner strip mined loop, reduce number of
  // iterations of the inner loop, set exit condition of outer loop,
  // construct required phi nodes for outer loop.
  if (outcnt() == 1) {
    Node* cmp = unique_out();
    if (cmp != NULL && cmp->outcnt() == 1 && cmp->Opcode() == Op_CmpI) {
      Node* test = cmp->unique_out();
      if (test != NULL && test->outcnt() == 1 && test->Opcode() == Op_Bool) {
        Node* lex = test->unique_out();
        if (lex != NULL && lex->Opcode() == Op_If) {
          IfNode* le = lex->as_If();
          Node* le_tail = le->proj_out(true);
          if (le_tail != NULL) {
            Node* lx = le_tail->unique_ctrl_out();
            if (lx != NULL && lx->is_Loop()) {
              LoopNode* l = lx->as_Loop();
              if (lx->as_Loop()->is_strip_mined() &&
                  le->in(0) != NULL &&
                  le->in(0)->in(0) != NULL) {
                Node* inner_clex = le->in(0)->in(0)->in(0);
                if (inner_clex != NULL && inner_clex->is_CountedLoopEnd()) {
                  CountedLoopEndNode* inner_cle = inner_clex->as_CountedLoopEnd();
                  Node* inner_clx = l->unique_ctrl_out();
                  if (inner_clx != NULL && inner_clx->is_CountedLoop()) {
                    CountedLoopNode* inner_cl = inner_clx->as_CountedLoop();
                    assert(inner_cl->is_strip_mined(), "inner loop should be strip mined");
                    PhaseIterGVN *igvn = phase->is_IterGVN();
                    Node* inner_iv_phi = inner_cl->phi();
                    if (inner_iv_phi != NULL) {
                      assert(cmp->in(1) == inner_cle->cmp_node()->in(1), "broken comparison");
                      assert(test->as_Bool()->_test._test == inner_cle->test_trip(), "broken comparison");

                      int stride = inner_cl->stride_con();
                      jlong scaled_iters_long = ((jlong)LoopStripMiningIter) * ABS(stride);
                      int scaled_iters = (int)scaled_iters_long;
                      int short_scaled_iters = LoopStripMiningIterShortLoop* ABS(stride);
                      const TypeInt* inner_iv_t = phase->type(inner_iv_phi)->is_int();
                      jlong iter_estimate = (jlong)inner_iv_t->_hi - (jlong)inner_iv_t->_lo;
                      assert(iter_estimate > 0, "broken");
                      if ((jlong)scaled_iters != scaled_iters_long || iter_estimate <= short_scaled_iters) {
                        // Remove outer loop and safepoint (too few iterations)
                        Node* outer_sfpt = le->in(0);
                        assert(outer_sfpt->Opcode() == Op_SafePoint, "broken outer loop");
                        Node* outer_out = le->proj_out(false);
                        igvn->replace_node(outer_out, outer_sfpt->in(0));
                        igvn->replace_input_of(outer_sfpt, 0, igvn->C->top());
                        inner_cl->clear_strip_mined();
                        return igvn->C->top();
                      }

                      Node* cle_tail = inner_cle->proj_out(true);
                      ResourceMark rm;
                      Node_List old_new;
                      if (cle_tail->outcnt() > 1) {
                        // Look for nodes on backedge of inner loop and clone them
                        Unique_Node_List backedge_nodes;
                        for (DUIterator_Fast imax, i = cle_tail->fast_outs(imax); i < imax; i++) {
                          Node* u = cle_tail->fast_out(i);
                          if (u != inner_cl) {
                            assert(!u->is_CFG(), "control flow on the backedge?");
                            backedge_nodes.push(u);
                          }
                        }
                        uint last = igvn->C->unique();
                        for (uint next = 0; next < backedge_nodes.size(); next++) {
                          Node* n = backedge_nodes.at(next);
                          old_new.map(n->_idx, n->clone());
                          for (DUIterator_Fast imax, i = n->fast_outs(imax); i < imax; i++) {
                            Node* u = n->fast_out(i);
                            assert(!u->is_CFG(), "broken");
                            if (u->_idx >= last) {
                              continue;
                            }
                            if (!u->is_Phi()) {
                              backedge_nodes.push(u);
                            } else {
                              assert(u->in(0) == inner_cl, "strange phi on the backedge");
                            }
                          }
                        }
                        // Put the clones on the outer loop backedge
                        for (uint next = 0; next < backedge_nodes.size(); next++) {
                          Node *n = old_new[backedge_nodes.at(next)->_idx];
                          for (uint i = 1; i < n->req(); i++) {
                            if (n->in(i) != NULL && old_new[n->in(i)->_idx] != NULL) {
                              n->set_req(i, old_new[n->in(i)->_idx]);
                            }
                          }
                          if (n->in(0) != NULL) {
                            assert(n->in(0) == cle_tail, "node not on backedge?");
                            n->set_req(0, le_tail);
                          }
                          igvn->register_new_node_with_optimizer(n);
                        }
                      }

                      Node* iv_phi = NULL;
                      // Make a clone of each phi in the inner loop
                      // for the outer loop
                      for (uint i = 0; i < inner_cl->outcnt(); i++) {
                        Node* u = inner_cl->raw_out(i);
                        if (u->is_Phi()) {
                          assert(u->in(0) == inner_cl, "");
                          Node* phi = u->clone();
                          phi->set_req(0, l);
                          Node* be = old_new[phi->in(LoopNode::LoopBackControl)->_idx];
                          if (be != NULL) {
                            phi->set_req(LoopNode::LoopBackControl, be);
                          }
                          phi = igvn->transform(phi);
                          igvn->replace_input_of(u, LoopNode::EntryControl, phi);
                          if (u == inner_iv_phi) {
                            iv_phi = phi;
                          }
                        }
                      }
                      Node* cle_out = le->in(0)->in(0);
                      if (cle_out->outcnt() > 1) {
                        // Look for chains of stores that were sunk
                        // out of the inner loop and are in the outer loop
                        for (DUIterator_Fast imax, i = cle_out->fast_outs(imax); i < imax; i++) {
                          Node* u = cle_out->fast_out(i);
                          if (u->is_Store()) {
                            Node* first = u;
                            for(;;) {
                              Node* next = first->in(MemNode::Memory);
                              if (!next->is_Store() || next->in(0) != cle_out) {
                                break;
                              }
                              first = next;
                            }
                            Node* last = u;
                            for(;;) {
                              Node* next = NULL;
                              for (DUIterator_Fast jmax, j = last->fast_outs(jmax); j < jmax; j++) {
                                Node* uu = last->fast_out(j);
                                if (uu->is_Store() && uu->in(0) == cle_out) {
                                  assert(next == NULL, "only one in the outer loop");
                                  next = uu;
                                }
                              }
                              if (next == NULL) {
                                break;
                              }
                              last = next;
                            }
                            Node* phi = NULL;
                            for (DUIterator_Fast jmax, j = l->fast_outs(jmax); j < jmax; j++) {
                              Node* uu = l->fast_out(j);
                              if (uu->is_Phi()) {
                                Node* be = uu->in(LoopNode::LoopBackControl);
                                while (be->is_Store() && old_new[be->_idx] != NULL) {
                                  ShouldNotReachHere();
                                  be = be->in(MemNode::Memory);
                                }
                                if (be == last || be == first->in(MemNode::Memory)) {
                                  assert(phi == NULL, "only one phi");
                                  phi = uu;
                                }
                              }
                            }
#ifdef ASSERT
                            for (DUIterator_Fast jmax, j = l->fast_outs(jmax); j < jmax; j++) {
                              Node* uu = l->fast_out(j);
                              if (uu->is_Phi() && uu->bottom_type() == Type::MEMORY) {
                                if (uu->adr_type() == igvn->C->get_adr_type(igvn->C->get_alias_index(u->adr_type()))) {
                                  assert(phi == uu, "what's that phi?");
                                } else if (uu->adr_type() == TypePtr::BOTTOM) {
                                  Node* n = uu->in(LoopNode::LoopBackControl);
                                  uint limit = igvn->C->live_nodes();
                                  uint i = 0;
                                  while (n != uu) {
                                    i++;
                                    assert(i < limit, "infinite loop");
                                    if (n->is_Proj()) {
                                      n = n->in(0);
                                    } else if (n->is_SafePoint() || n->is_MemBar()) {
                                      n = n->in(TypeFunc::Memory);
                                    } else if (n->is_Phi()) {
                                      n = n->in(1);
                                    } else if (n->is_MergeMem()) {
                                      n = n->as_MergeMem()->memory_at(igvn->C->get_alias_index(u->adr_type()));
                                    } else if (n->is_Store() || n->is_LoadStore() || n->is_ClearArray()) {
                                      n = n->in(MemNode::Memory);
                                    } else {
                                      n->dump();
                                      ShouldNotReachHere();
                                    }
                                  }
                                }
                              }
                            }
#endif
                            if (phi == NULL) {
                              // If the an entire chains was sunk, the
                              // inner loop has no phi for that memory
                              // slice, create one for the outer loop
                              phi = PhiNode::make(l, first->in(MemNode::Memory), Type::MEMORY,
                                                  igvn->C->get_adr_type(igvn->C->get_alias_index(u->adr_type())));
                              phi->set_req(LoopNode::LoopBackControl, last);
                              phi = igvn->transform(phi);
                              igvn->replace_input_of(first, MemNode::Memory, phi);
                            } else {
                              // Or fix the outer loop fix to include
                              // that chain of stores.
                              Node* be = phi->in(LoopNode::LoopBackControl);
                              while (be->is_Store() && old_new[be->_idx] != NULL) {
                                ShouldNotReachHere();
                                be = be->in(MemNode::Memory);
                              }
                              if (be == first->in(MemNode::Memory)) {
                                if (be == phi->in(LoopNode::LoopBackControl)) {
                                  igvn->replace_input_of(phi, LoopNode::LoopBackControl, last);
                                } else {
                                  igvn->replace_input_of(be, MemNode::Memory, last);
                                }
                              } else {
#ifdef ASSERT
                                if (be == phi->in(LoopNode::LoopBackControl)) {
                                  assert(phi->in(LoopNode::LoopBackControl) == last, "");
                                } else {
                                  assert(be->in(MemNode::Memory) == last, "");
                                }
#endif
                              }
                            }
                          }
                        }
                      }

                      if (iv_phi != NULL) {
                        // Now adjust the inner loop's exit condition
                        Node* limit = inner_cl->limit();
                        Node* sub = NULL;
                        if (stride > 0) {
                          sub = igvn->transform(new SubINode(limit, iv_phi));
                        } else {
                          sub = igvn->transform(new SubINode(iv_phi, limit));
                        }
                        Node* min = igvn->transform(new MinINode(sub, igvn->intcon(scaled_iters)));
                        Node* new_limit = NULL;
                        if (stride > 0) {
                          new_limit = igvn->transform(new AddINode(min, iv_phi));
                        } else {
                          new_limit = igvn->transform(new SubINode(iv_phi, min));
                        }
                        igvn->replace_input_of(inner_cle->cmp_node(), 2, new_limit);
                        if (iter_estimate <= scaled_iters_long) {
                          // We would only go through one iteration of
                          // the outer loop: drop the outer loop but
                          // keep the safepoint so we don't run for
                          // too long without a safepoint
                          igvn->replace_input_of(le, 1, igvn->intcon(0));
                          inner_cl->clear_strip_mined();
                        }
                        return limit;
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
  return NULL;
}

//=============================================================================

uint ProfileBooleanNode::hash() const { return NO_HASH; }
uint ProfileBooleanNode::cmp( const Node &n ) const {
  return (&n == this);
}

Node *ProfileBooleanNode::Ideal(PhaseGVN *phase, bool can_reshape) {
  if (can_reshape && _delay_removal) {
    _delay_removal = false;
    return this;
  } else {
    return NULL;
  }
}

Node* ProfileBooleanNode::Identity(PhaseGVN* phase) {
  if (_delay_removal) {
    return this;
  } else {
    assert(_consumed, "profile should be consumed before elimination");
    return in(1);
  }
}
