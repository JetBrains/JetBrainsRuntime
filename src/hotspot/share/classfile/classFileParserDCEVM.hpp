/*
 * Copyright (c) 1997, 2023, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_CLASSFILE_CLASSFILEPARSER_DCEVM_HPP
#define SHARE_CLASSFILE_CLASSFILEPARSER_DCEVM_HPP

#include "utilities/resourceHash.hpp"
#include "memory/allocation.hpp"
#include "oops/klass.hpp"

inline bool old2new_ptr_equals(Klass* const& a, Klass* const& b) { return a == b; }
inline unsigned old2new_ptr_hash(Klass* const& p) { return (unsigned)((uintptr_t)p * 2654435761u); }

using Old2NewKlassMap =
        ResourceHashtable<Klass*, Klass*, 1031, AnyObj::C_HEAP, mtInternal, old2new_ptr_hash, old2new_ptr_equals>;

#endif //SHARE_CLASSFILE_CLASSFILEPARSER_DCEVM_HPP
