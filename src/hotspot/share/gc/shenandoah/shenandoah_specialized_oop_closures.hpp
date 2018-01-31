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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAH_SPECIALIZED_OOP_CLOSURES_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAH_SPECIALIZED_OOP_CLOSURES_HPP

class ShenandoahMarkUpdateRefsClosure;
class ShenandoahMarkUpdateRefsDedupClosure;
class ShenandoahMarkUpdateRefsMetadataClosure;
class ShenandoahMarkUpdateRefsMetadataDedupClosure;
class ShenandoahMarkRefsClosure;
class ShenandoahMarkRefsDedupClosure;
class ShenandoahMarkRefsMetadataClosure;
class ShenandoahMarkRefsMetadataDedupClosure;
class ShenandoahUpdateHeapRefsClosure;
class ShenandoahUpdateHeapRefsMatrixClosure;
class ShenandoahPartialEvacuateUpdateHeapClosure;
class ShenandoahTraversalClosure;
class ShenandoahTraversalMetadataClosure;
class ShenandoahTraversalDedupClosure;
class ShenandoahTraversalMetadataDedupClosure;

#define SPECIALIZED_OOP_OOP_ITERATE_CLOSURES_SHENANDOAH(f) \
      f(ShenandoahMarkUpdateRefsClosure,_nv)               \
      f(ShenandoahMarkUpdateRefsMetadataClosure,_nv)       \
      f(ShenandoahMarkRefsClosure,_nv)                     \
      f(ShenandoahMarkRefsMetadataClosure,_nv)             \
      f(ShenandoahUpdateHeapRefsClosure,_nv)               \
      f(ShenandoahUpdateHeapRefsMatrixClosure,_nv)         \
      f(ShenandoahTraversalClosure,_nv)                    \
      f(ShenandoahTraversalMetadataClosure,_nv)            \
      f(ShenandoahPartialEvacuateUpdateHeapClosure,_nv)    \
      f(ShenandoahMarkUpdateRefsDedupClosure,_nv)          \
      f(ShenandoahMarkUpdateRefsMetadataDedupClosure,_nv)  \
      f(ShenandoahMarkRefsDedupClosure,_nv)                \
      f(ShenandoahMarkRefsMetadataDedupClosure,_nv)        \
      f(ShenandoahTraversalDedupClosure,_nv)               \
      f(ShenandoahTraversalMetadataDedupClosure,_nv)

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAH_SPECIALIZED_OOP_CLOSURES_HPP
